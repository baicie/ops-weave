import copy
import json
from pathlib import Path
import pytest
from jsonschema import Draft202012Validator, FormatChecker, ValidationError
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = Registry().with_resources((p.as_uri(), Resource.from_contents(json.loads(p.read_text(encoding="utf-8"))))
    for p in (ROOT / "contracts/schemas").rglob("*.schema.json"))

def sample(name):
    return json.loads((ROOT / "contracts/examples" / (name + ".json")).read_text(encoding="utf-8"))

def validate(name, value):
    uri = (ROOT / "contracts/schemas/v1" / (name + ".schema.json")).as_uri()
    Draft202012Validator({"$ref": uri}, registry=REGISTRY, format_checker=FormatChecker()).validate(value)

@pytest.mark.parametrize("name,field,value", [
    ("tool-read-session-request", "tenantId", "other"),
    ("tool-read-session-request", "knowledgeMode", "historical"),
    ("tool-read-session-request", "asOf", "2020-01-01T00:00:00Z"),
    ("tool-read-session", "maxToolCalls", 100),
    ("tool-read-session", "permissions", ["incident.manage"]),
    ("tool-read-session", "allowedTools", ["shell@1.0.0"]),
    ("tool-read-session", "policyVersion", "client-policy"),
    ("metric-tool-request", "maxPoints", 501),
    ("metric-tool-request", "entityId", "11111111-1111-1111-1111-111111111111"),
    ("incident-tool-request", "permissions", ["incident.read"]),
    ("platform-evidence", "knowledgeMode", "historical"),
    ("platform-evidence", "dataModes", ["real"]),
    ("platform-evidence", "warnings", []),
    ("platform-evidence", "producerTool", "shell@1.0.0"),
    ("tool-result", "truncated", True),
    ("tool-result", "evidenceRefs", []),
])
def test_invalid_tool_boundaries(name, field, value):
    body = sample(name); body[field] = value
    with pytest.raises(ValidationError): validate(name, body)

@pytest.mark.parametrize("field,value", [("sourceRef", "https://attacker.example/secret"), ("trust", "trusted"), ("availableAt", "2026-02-30T00:00:00Z"), ("kind", "action")])
def test_evidence_metadata(field, value):
    body = sample("platform-evidence"); body["evidence"][field] = value
    with pytest.raises(ValidationError): validate("platform-evidence", body)

def test_new_tool_versions_resolve_canonical_output_and_reject_identity():
    for p in (ROOT / "contracts/tools").glob("*.v2.tool.json"):
        tool = json.loads(p.read_text(encoding="utf-8"))
        output = copy.deepcopy(tool["outputSchema"])
        output["$ref"] = (p.parent / output["$ref"]).resolve().as_uri()
        Draft202012Validator(output, registry=REGISTRY, format_checker=FormatChecker()).validate(sample("tool-result"))
        kind = tool["id"].split(".")[0]
        args = sample(kind + "-tool-request"); args["tenantId"] = "other"
        with pytest.raises(ValidationError): Draft202012Validator(tool["inputSchema"]).validate(args)
