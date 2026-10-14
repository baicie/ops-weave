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


@pytest.mark.parametrize("name", ["entity-deletion-command", "entity-deletion-receipt"])
def test_explicit_fixtures(name):
    validator(name).validate(sample(name))


def test_command_is_reasoned_idempotent_and_closed():
    body = sample("entity-deletion-command")
    for field in ["requestId", "entityId", "reason"]:
        missing = dict(body)
        del missing[field]
        rejects("entity-deletion-command", missing)
    for key, value in [("requestId", "not-a-uuid"), ("entityId", "not-a-uuid"), ("reason", ""),
                       ("reason", "x" * 257), ("reason", "line\nbreak"), ("cascade", True),
                       ("tenantId", "other"), ("actor", "someone"), ("dryRun", True)]:
        broken = dict(body)
        broken[key] = value
        rejects("entity-deletion-command", broken)


def test_receipt_reports_the_retained_counts_and_never_a_cascade():
    receipt = sample("entity-deletion-receipt")
    assert receipt["auditCount"] == 1 and receipt["keptProtected"] == 0 and receipt["keptUnfinished"] == 0
    rejects("entity-deletion-receipt", dict(receipt, auditCount=0))
    rejects("entity-deletion-receipt", dict(receipt, outcome="DELETED"))
    rejects("entity-deletion-receipt", dict(receipt, summary=""))
    rejects("entity-deletion-receipt", dict(receipt, reason=""))
    rejects("entity-deletion-receipt", dict(receipt, removed=1))
    rejects("entity-deletion-receipt", dict(receipt, schemaVersion="2.0"))
    protected = dict(receipt, outcome="PROTECTED", summary="The entity is still referenced by a current record and cannot be deleted.")
    validator("entity-deletion-receipt").validate(protected)
    rejects("entity-deletion-receipt", dict(protected, auditCount=2))


def test_deletion_routes_are_registered_with_closed_schemas():
    expected = [
        ("/api/v1/entities/{entityId}/deletions", "post", "v2/entity-deletion-command.schema.json", "deleteEntity"),
        ("/api/v1/entities/deletions/commands/{requestId}", "get", "v2/entity-deletion-receipt.schema.json", "getEntityDeletionCommand"),
    ]
    for path, method, schema, operation_id in expected:
        assert_operation(path, method, schema, operation_id)
    operation_ids = re.findall(r"^      operationId: ([^\s]+)$", OPENAPI.read_text(encoding="utf-8"), re.MULTILINE)
    assert len(operation_ids) == len(set(operation_ids))
    for name in ["entity-deletion-command.schema.json", "entity-deletion-receipt.schema.json"]:
        assert '"additionalProperties": false' in (ROOT / "contracts/schemas/v2" / name).read_text(encoding="utf-8")
    section = _section("/api/v1/entities/{entityId}/deletions")
    for code in ["'400'", "'401'", "'403'", "'404'", "'409'", "'503'"]:
        assert code in section
