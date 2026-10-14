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


@pytest.mark.parametrize("name", ["receipt-retention-report", "receipt-retention-budget", "receipt-retention-preview",
                                  "receipt-retention-preview-command", "receipt-retention-confirm-command",
                                  "receipt-retention-receipt"])
def test_explicit_fixtures(name):
    validator(name).validate(sample(name))


def test_kinds_and_scopes_are_closed():
    for name, field in [("receipt-retention-report", "kind"), ("receipt-retention-preview", "kind"),
                        ("receipt-retention-confirm-command", "kind"), ("receipt-retention-receipt", "kind")]:
        broken = dict(sample(name))
        broken[field] = "scan-run"
        rejects(name, broken)
    for name in ["receipt-retention-report", "receipt-retention-preview", "receipt-retention-confirm-command"]:
        broken = dict(sample(name))
        broken["scopeKey"] = "bad scope"
        rejects(name, broken)
        forged = dict(sample(name))
        forged["tenantId"] = "other"
        rejects(name, forged)


def test_budget_status_matches_the_published_threshold():
    report = sample("receipt-retention-report")
    assert report["kept"] == 120 and report["maxPerScope"] == 1000 and report["status"] == "NEAR_LIMIT"
    at_limit = dict(report, kept=1000, keptProtected=0, status="AT_LIMIT",
                    summary="The receipt budget is used up for this scope; run the explicit cleanup.")
    validator("receipt-retention-report").validate(at_limit)
    rejects("receipt-retention-report", dict(at_limit, status="OK"))
    rejects("receipt-retention-report", dict(report, kept=-1))
    rejects("receipt-retention-report", dict(report, maxPerScope=1001))
    rejects("receipt-retention-report", dict(report, maxPerTenant=5001))
    rejects("receipt-retention-report", dict(report, summary=""))
    budget = sample("receipt-retention-budget")
    rejects("receipt-retention-budget", dict(budget, items=[], count=1))
    rejects("receipt-retention-budget", dict(budget, dataMode="scan-log"))
    rejects("receipt-retention-budget", dict(budget, removed=2))
    rejects("receipt-retention-budget", dict(budget, count=0))


def test_preview_is_read_only_and_keeps_both_counts():
    preview = sample("receipt-retention-preview")
    validator("receipt-retention-preview").validate(dict(preview, candidates=[], removable=0))
    # `removable` must equal the candidate count; the JSON Schema cannot compare an array length to a
    # number, so that invariant is enforced by the domain constructor and its smoke test.
    rejects("receipt-retention-preview", dict(preview, keptUnfinished=1))
    rejects("receipt-retention-preview", dict(preview, maxRemove=0))
    rejects("receipt-retention-preview", dict(preview, maxRemove=101))
    rejects("receipt-retention-preview", dict(preview, previewDigest="sha256:zz"))
    rejects("receipt-retention-preview", dict(preview, applied=True))
    rejects("receipt-retention-preview-command", dict(sample("receipt-retention-preview-command"), maxRemove=101))
    rejects("receipt-retention-preview-command", dict(sample("receipt-retention-preview-command"), reason="nope"))


def test_confirm_command_is_idempotent_shaped_and_never_scheduled():
    body = sample("receipt-retention-confirm-command")
    for field in ["requestId", "kind", "scopeKey", "previewDigest", "reason", "maxRemove"]:
        missing = dict(body)
        del missing[field]
        rejects("receipt-retention-confirm-command", missing)
    for key, value in [("requestId", "not-a-uuid"), ("reason", ""), ("previewDigest", "sha256:zz"),
                       ("maxRemove", 0), ("schedule", "daily")]:
        broken = dict(body)
        broken[key] = value
        rejects("receipt-retention-confirm-command", broken)


def test_cleanup_receipt_keeps_the_retained_counts():
    receipt = sample("receipt-retention-receipt")
    assert receipt["removed"] == 2 and receipt["keptProtected"] == 880 and receipt["keptUnfinished"] == 0
    rejects("receipt-retention-receipt", dict(receipt, keptUnfinished=1))
    rejects("receipt-retention-receipt", dict(receipt, removed=-1))
    rejects("receipt-retention-receipt", dict(receipt, reason=""))
    rejects("receipt-retention-receipt", dict(receipt, schemaVersion="2.0"))
    rejects("receipt-retention-receipt", dict(receipt, replayed=True))


def test_retention_routes_are_registered_with_closed_schemas():
    expected = [
        ("/api/v1/integrations/receipts/retention", "get", "v2/receipt-retention-budget.schema.json", "getReceiptRetentionBudgets"),
        ("/api/v1/integrations/receipts/retention/preview", "post", "v2/receipt-retention-preview-command.schema.json", "previewReceiptRetentionCleanup"),
        ("/api/v1/integrations/receipts/retention/confirm", "post", "v2/receipt-retention-confirm-command.schema.json", "confirmReceiptRetentionCleanup"),
        ("/api/v1/integrations/receipts/retention/commands/{requestId}", "get", "v2/receipt-retention-receipt.schema.json", "getReceiptRetentionCommand"),
    ]
    for path, method, schema, operation_id in expected:
        assert_operation(path, method, schema, operation_id)
    operation_ids = re.findall(r"^      operationId: ([^\s]+)$", OPENAPI.read_text(encoding="utf-8"), re.MULTILINE)
    assert len(operation_ids) == len(set(operation_ids))
    for name in ["receipt-retention-preview-command.schema.json", "receipt-retention-confirm-command.schema.json"]:
        assert '"additionalProperties": false' in (ROOT / "contracts/schemas/v2" / name).read_text(encoding="utf-8")
