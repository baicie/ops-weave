import json
from pathlib import Path
import re

import pytest
from jsonschema import Draft202012Validator, FormatChecker, ValidationError
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[2]
OPENAPI = ROOT / "contracts/openapi/platform-draft.yaml"


def sample(name):
    return json.loads((ROOT / "contracts/examples/v2" / f"{name}.json").read_text(encoding="utf-8"))


def validator(name):
    registry = Registry()
    for path in (ROOT / "contracts/schemas").rglob("*.json"):
        schema = json.loads(path.read_text(encoding="utf-8"))
        if "$id" in schema:
            registry = registry.with_resource(schema["$id"], Resource.from_contents(schema))
    return Draft202012Validator(json.loads((ROOT / "contracts/schemas/v2" / f"{name}.schema.json").read_text(encoding="utf-8")),
                                registry=registry, format_checker=FormatChecker())


def rejects(name, value):
    with pytest.raises(ValidationError):
        validator(name).validate(value)


def _section(path):
    text = OPENAPI.read_text(encoding="utf-8")
    marker = f"  {path}:\n"
    start = text.index(marker)
    next_path = re.search(r"\n  /[^\n]+:\n", text[start + len(marker):])
    end = start + len(marker) + next_path.start() if next_path else len(text)
    return text[start:end]


def assert_operation(path, method, schema, operation_id):
    section = _section(path)
    match = re.search(rf"\n    {method}:\n(?P<body>.*?)(?=\n    (?:get|post|patch|put|delete|options|head|trace):|\Z)", section, re.DOTALL)
    assert match, f"missing OpenAPI operation: {method.upper()} {path}"
    body = match.group("body")
    assert f"operationId: {operation_id}" in body
    assert f"../schemas/{schema}" in body
    assert (ROOT / "contracts/schemas" / schema).is_file()


@pytest.mark.parametrize("name", ["workflow-collection-binding-command", "workflow-collection-binding",
                                  "workflow-collection-binding-receipt", "workflow-collection-binding-page"])
def test_explicit_fixtures(name):
    validator(name).validate(sample(name))


def test_commands_are_closed_and_never_carry_trust():
    body = sample("workflow-collection-binding-command")
    for field in ["tenantId", "owner", "actor", "permissions", "dryRun", "start", "token"]:
        forged = dict(body)
        forged[field] = "forged"
        rejects("workflow-collection-binding-command", forged)
    for field in ["requestId", "id", "revision", "digest", "sourceInstanceId", "operation", "reason"]:
        missing = dict(body)
        del missing[field]
        rejects("workflow-collection-binding-command", missing)
    for key, value in [("operation", "START"), ("operation", "publish"), ("id", "Bound-Flow"), ("revision", 0),
                       ("digest", "sha256:zz"), ("sourceInstanceId", "bad source!"), ("reason", ""),
                       ("requestId", "not-a-uuid")]:
        broken = dict(body)
        broken[key] = value
        rejects("workflow-collection-binding-command", broken)


def test_binding_state_is_a_closed_pinned_version():
    body = sample("workflow-collection-binding")
    for key, value in [("state", "ENABLED"), ("generation", 0), ("sourceInstanceId", "bad!"),
                       ("updatedAt", "yesterday"), ("reason", "x" * 257)]:
        broken = dict(body)
        broken[key] = value
        rejects("workflow-collection-binding", broken)
    broken = dict(body)
    broken["extra"] = True
    rejects("workflow-collection-binding", broken)


def test_receipt_is_immutable_and_pins_its_binding():
    body = sample("workflow-collection-binding-receipt")
    assert body["binding"] == sample("workflow-collection-binding")
    broken = dict(body)
    del broken["binding"]
    rejects("workflow-collection-binding-receipt", broken)
    broken = dict(body)
    broken["schemaVersion"] = "1.0"
    rejects("workflow-collection-binding-receipt", broken)
    broken = dict(body)
    broken["replayed"] = True
    rejects("workflow-collection-binding-receipt", broken)
    broken = dict(body)
    broken["binding"] = dict(body["binding"], state="UNBOUND")
    validator("workflow-collection-binding-receipt").validate(broken)


def test_page_count_agrees_with_items():
    body = sample("workflow-collection-binding-page")
    validator("workflow-collection-binding-page").validate({**body, "items": [], "count": 0})
    rejects("workflow-collection-binding-page", {**body, "items": [], "count": 1})
    rejects("workflow-collection-binding-page", {**body, "count": 0})
    rejects("workflow-collection-binding-page", {**body, "truncated": True})
    rejects("workflow-collection-binding-page", {**body, "items": [body["items"][0]] * 201, "count": 201})


def test_collection_binding_routes_are_registered_with_closed_schemas():
    expected = [
        ("/api/v1/integrations/workflows/collection-bindings", "get", "v2/workflow-collection-binding-page.schema.json", "listWorkflowCollectionBindings"),
        ("/api/v1/integrations/workflows/collection-bindings/enable", "post", "v2/workflow-collection-binding-command.schema.json", "enableWorkflowCollectionBinding"),
        ("/api/v1/integrations/workflows/collection-bindings/disable", "post", "v2/workflow-collection-binding-command.schema.json", "disableWorkflowCollectionBinding"),
        ("/api/v1/integrations/workflows/collection-bindings/commands/{requestId}", "get", "v2/workflow-collection-binding-receipt.schema.json", "getWorkflowCollectionBindingCommand"),
    ]
    for path, method, schema, operation_id in expected:
        assert_operation(path, method, schema, operation_id)
    text = OPENAPI.read_text(encoding="utf-8")
    for name in ["workflow-collection-binding-command.schema.json", "workflow-collection-binding.schema.json"]:
        assert '"additionalProperties": false' in (ROOT / "contracts/schemas/v2" / name).read_text(encoding="utf-8")
    operation_ids = re.findall(r"^      operationId: ([^\s]+)$", text, re.MULTILINE)
    assert len(operation_ids) == len(set(operation_ids))
    for code in ["'400'", "'401'", "'403'", "'404'", "'409'", "'503'"]:
        assert code in _section("/api/v1/integrations/workflows/collection-bindings/enable")
