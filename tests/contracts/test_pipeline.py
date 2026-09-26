import json
from pathlib import Path
import pytest
from jsonschema import Draft202012Validator, FormatChecker, ValidationError
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = Registry().with_resources((p.as_uri(), Resource.from_contents(json.loads(p.read_text(encoding="utf-8"))))
    for p in (ROOT / "contracts/schemas").rglob("*.schema.json"))

def validate(name, value):
    schema = ROOT / f"contracts/schemas/v1/{name}.schema.json"
    Draft202012Validator({"$ref": schema.as_uri()}, registry=REGISTRY, format_checker=FormatChecker()).validate(value)

@pytest.mark.parametrize("name", ["pipeline-preview-request", "pipeline-replay-request", "pipeline-replay-run-request", "pipeline-definition", "pipeline-draft-save"])
@pytest.mark.parametrize("field", ["tenantId", "permissions", "endpoint"])
def test_requests_cannot_override_trusted_context(name, field):
    value = json.loads((ROOT / f"contracts/examples/{name}.json").read_text())
    value[field] = "attacker"
    with pytest.raises(ValidationError): validate(name, value)

@pytest.mark.parametrize("field,value", [("dryRun", False), ("limit", 101), ("purpose", "WRITE_INVENTORY")])
def test_replay_is_bounded_and_read_only(field, value):
    request = json.loads((ROOT / "contracts/examples/pipeline-replay-request.json").read_text())
    request[field] = value
    with pytest.raises(ValidationError): validate("pipeline-replay-request", request)

@pytest.mark.parametrize("index,config", [(2, {"script": "forbidden"}), (1, {"displayNameField": "host"}), (2, {"displayNameField": "arbitrary.path"})])
def test_mapping_configuration_is_allowlisted(index, config):
    request = json.loads((ROOT / "contracts/examples/pipeline-definition.json").read_text())
    request["nodes"][index]["config"] = config
    with pytest.raises(ValidationError): validate("pipeline-definition", request)

def test_allowed_mapping_option():
    request = json.loads((ROOT / "contracts/examples/pipeline-definition.json").read_text())
    request["nodes"][2]["config"] = {"displayNameField": "host"}
    validate("pipeline-definition", request)

@pytest.mark.parametrize("field,value", [("requestKey", "1-1-1-1-1"), ("dryRun", False), ("limit", 101), ("owner", "attacker")])
def test_durable_request_bounds(field, value):
    request = json.loads((ROOT / "contracts/examples/pipeline-replay-run-request.json").read_text())
    request[field] = value
    with pytest.raises(ValidationError): validate("pipeline-replay-run-request", request)

@pytest.mark.parametrize("state", ["RUNNING", "FAILED"])
def test_unfinished_run_cannot_claim_report(state):
    result = json.loads((ROOT / "contracts/examples/pipeline-replay-run.json").read_text())
    result["run"].update(state=state, leaseUntil="2026-09-25T00:02:00Z" if state == "RUNNING" else None,
                         failureCode="NOT_FOUND" if state == "FAILED" else None, canResume=state == "FAILED")
    with pytest.raises(ValidationError): validate("pipeline-replay-run", result)
    result["report"] = None
    validate("pipeline-replay-run", result)

@pytest.mark.parametrize("field,value", [("attempt", 4), ("canResume", True), ("failureCode", "private upstream error"), ("writesPerformed", True)])
def test_completed_run_invariants(field, value):
    result = json.loads((ROOT / "contracts/examples/pipeline-replay-run.json").read_text())
    result["run"][field] = value
    with pytest.raises(ValidationError): validate("pipeline-replay-run", result)

def test_published_sample_digest_matches_canonical_content():
    import hashlib
    import struct
    sample = json.loads((ROOT / "contracts/examples/pipeline-version.json").read_text())
    definition = sample["definition"]
    content = bytearray()
    def text(value):
        # All digest strings in this v1 allowlist are ASCII: Java writeUTF is length + UTF-8.
        encoded = value.encode("ascii")
        content.extend(struct.pack(">H", len(encoded)))
        content.extend(encoded)
    text(sample["engine"])
    text(definition["id"])
    content.extend(struct.pack(">i", definition["revision"]))
    text(definition["source"]["type"])
    text(definition["source"]["objectType"])
    text(definition["errorPolicy"])
    by_id = {node["id"]: node for node in definition["nodes"]}
    next_id = {edge["from"]: edge["to"] for edge in definition["edges"]}
    node_id = next(node["id"] for node in definition["nodes"] if node["type"] == "Source")
    while node_id:
        node = by_id[node_id]
        text(node_id)
        text(node["type"])
        config = node.get("config", {})
        content.extend(struct.pack(">i", len(config)))
        for key in sorted(config):
            text(key)
            text(config[key])
        node_id = next_id.get(node_id)
    assert sample["digest"] == "sha256:" + hashlib.sha256(content).hexdigest()

@pytest.mark.parametrize("version", [-1, 2147483647, "1", 1.5, None])
def test_draft_save_requires_bounded_edit_version(version):
    request = json.loads((ROOT / "contracts/examples/pipeline-draft-save.json").read_text())
    request["expectedEditVersion"] = version
    with pytest.raises(ValidationError): validate("pipeline-draft-save", request)

def test_draft_response_cannot_claim_publication():
    response = json.loads((ROOT / "contracts/examples/pipeline-draft.json").read_text())
    response["state"] = "PUBLISHED"
    with pytest.raises(ValidationError): validate("pipeline-draft", response)

def test_recent_drafts_are_bounded_and_cannot_contain_other_identity():
    response = json.loads((ROOT / "contracts/examples/pipeline-draft-list.json").read_text())
    response["items"] = response["items"] * 51
    with pytest.raises(ValidationError): validate("pipeline-draft-list", response)
    response["items"] = response["items"][:1]
    response["items"][0]["owner"] = "someone"
    with pytest.raises(ValidationError): validate("pipeline-draft-list", response)
