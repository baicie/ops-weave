import json
from pathlib import Path

import pytest
import yaml
from jsonschema import Draft202012Validator, FormatChecker, ValidationError
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / "contracts" / "schemas" / "v1"
REGISTRY = Registry().with_resources((p.as_uri(), Resource.from_contents(json.loads(p.read_text(encoding="utf-8"))))
    for p in SCHEMAS.glob("*.schema.json"))


def validate(name, value):
    schema = SCHEMAS / f"{name}.schema.json"
    Draft202012Validator({"$ref": schema.as_uri()}, registry=REGISTRY, format_checker=FormatChecker()).validate(value)


RELATION_ID = "22222222-2222-4222-8222-222222222222"


def confirmation_command():
    return {
        "requestId": "11111111-1111-4111-8111-111111111111",
        "sourceKind": "source",
        "sourceKey": "workflow.source-a",
        "batchRef": "batch-42",
        "dataMode": "fixture",
    }


def withdraw_command():
    return {
        "requestId": "33333333-3333-4333-8333-333333333333",
        "sourceKind": "source",
        "sourceKey": "workflow.source-a",
        "batchRef": "batch-42",
        "reason": "来源批次撤销",
    }


def confirmed_entry():
    return {
        "sourceKind": "source", "sourceKey": "workflow.source-a", "batchRef": "batch-42",
        "dataMode": "fixture", "state": "CONFIRMED", "confirmedAt": "2026-10-10T00:00:00Z",
        "withdrawnAt": None, "withdrawReason": None,
    }


def withdrawn_entry():
    return {
        "sourceKind": "manual", "sourceKey": "operator", "batchRef": "assert-1",
        "dataMode": "unknown", "state": "WITHDRAWN", "confirmedAt": "2026-10-10T00:00:00Z",
        "withdrawnAt": "2026-10-10T01:00:00Z", "withdrawReason": "人工撤回断言",
    }


def test_confirmation_commands_are_closed():
    validate("entity-relation-confirmation-command", confirmation_command())
    validate("entity-relation-confirmation-command", {
        "requestId": confirmation_command()["requestId"], "sourceKind": "manual",
        "sourceKey": "operator", "batchRef": "assert-1", "dataMode": "unknown",
    })
    validate("entity-relation-confirmation-withdraw-command", withdraw_command())


@pytest.mark.parametrize("schema_name,patch", [
    ("entity-relation-confirmation-command", {"tenantId": "tenant-a"}),
    ("entity-relation-confirmation-command", {"actor": "operator"}),
    ("entity-relation-confirmation-command", {"sourceKind": "vendor"}),
    ("entity-relation-confirmation-command", {"sourceKey": ""}),
    ("entity-relation-confirmation-command", {"sourceKey": "x" * 129}),
    ("entity-relation-confirmation-command", {"sourceKey": "bad\nkey"}),
    ("entity-relation-confirmation-command", {"batchRef": ""}),
    ("entity-relation-confirmation-command", {"batchRef": "x" * 129}),
    ("entity-relation-confirmation-command", {"dataMode": "real"}),
    ("entity-relation-confirmation-command", {"requestId": "not-a-uuid"}),
    ("entity-relation-confirmation-command", {"unexpected": True}),
    ("entity-relation-confirmation-withdraw-command", {"tenantId": "tenant-a"}),
    ("entity-relation-confirmation-withdraw-command", {"actor": "operator"}),
    ("entity-relation-confirmation-withdraw-command", {"sourceKind": "Source"}),
    ("entity-relation-confirmation-withdraw-command", {"reason": ""}),
    ("entity-relation-confirmation-withdraw-command", {"reason": "x" * 257}),
    ("entity-relation-confirmation-withdraw-command", {"reason": "bad\nreason"}),
    ("entity-relation-confirmation-withdraw-command", {"batchRef": "x" * 129}),
    ("entity-relation-confirmation-withdraw-command", {"dataMode": "unknown"}),
    ("entity-relation-confirmation-withdraw-command", {"requestId": None}),
])
def test_confirmation_commands_reject_tenant_actor_and_invalid_fields(schema_name, patch):
    value = withdraw_command() if "withdraw" in schema_name else confirmation_command()
    value.update(patch)
    with pytest.raises(ValidationError):
        validate(schema_name, value)


def test_confirmation_entries_cover_both_states():
    validate("entity-relation-confirmation-entry", confirmed_entry())
    validate("entity-relation-confirmation-entry", withdrawn_entry())


@pytest.mark.parametrize("patch", [
    {"state": "PENDING"}, {"state": "CONFIRMED", "withdrawnAt": "2026-10-10T01:00:00Z"},
    {"state": "CONFIRMED", "withdrawReason": "理由"}, {"state": "WITHDRAWN", "withdrawnAt": None},
    {"state": "WITHDRAWN", "withdrawReason": None}, {"state": "WITHDRAWN", "withdrawReason": "x" * 257},
    {"sourceKind": "vendor"}, {"batchRef": ""}, {"dataMode": "real"},
    {"confirmedAt": "not-a-time"}, {"extra": True},
])
def test_confirmation_entry_rejects_inconsistent_or_invalid_fields(patch):
    value = confirmed_entry() if str(patch.get("state", "CONFIRMED")) == "CONFIRMED" else withdrawn_entry()
    value.update(patch)
    with pytest.raises(ValidationError):
        validate("entity-relation-confirmation-entry", value)


def test_confirmation_receipt_and_view_are_closed():
    validate("entity-relation-confirmation-receipt", {
        "schemaVersion": "1.0", "requestId": confirmation_command()["requestId"], "replayed": False,
        "relationId": RELATION_ID, "entry": confirmed_entry(),
    })
    validate("entity-relation-confirmation-receipt", {
        "schemaVersion": "1.0", "requestId": withdraw_command()["requestId"], "replayed": True,
        "relationId": RELATION_ID, "entry": withdrawn_entry(),
    })
    validate("entity-relation-confirmation-view", {
        "schemaVersion": "1.0", "storage": "postgres", "tenantId": "tenant-a", "relationId": RELATION_ID,
        "relationState": "ACTIVE", "entries": [confirmed_entry(), confirmed_entry(), withdrawn_entry()],
    })


@pytest.mark.parametrize("patch", [
    {"schemaVersion": "2.0"}, {"replayed": "yes"}, {"relationId": "x"}, {"entry": None},
    {"tenantId": "tenant-a"},
])
def test_confirmation_receipt_rejects_invalid_fields(patch):
    value = {
        "schemaVersion": "1.0", "requestId": confirmation_command()["requestId"], "replayed": False,
        "relationId": RELATION_ID, "entry": confirmed_entry(),
    }
    value.update(patch)
    with pytest.raises(ValidationError):
        validate("entity-relation-confirmation-receipt", value)


def test_confirmation_view_bounds_entries_and_relation_state():
    view = {
        "schemaVersion": "1.0", "storage": "memory", "tenantId": "tenant-a", "relationId": RELATION_ID,
        "relationState": "TERMINATED", "entries": [confirmed_entry()] * 51,
    }
    with pytest.raises(ValidationError):
        validate("entity-relation-confirmation-view", view)
    view["entries"] = []
    view["relationState"] = "CLOSED"
    with pytest.raises(ValidationError):
        validate("entity-relation-confirmation-view", view)
    view["relationState"] = "ACTIVE"
    view["unexpected"] = True
    with pytest.raises(ValidationError):
        validate("entity-relation-confirmation-view", view)


def test_confirmation_openapi_is_registered_with_closed_errors():
    api = yaml.safe_load((ROOT / "contracts/openapi/platform-draft.yaml").read_text(encoding="utf-8"))
    confirmations = api["paths"]["/api/v1/entities/{entityId}/relations/{relationId}/confirmations"]
    assert {"$ref": "#/components/parameters/clientRequestId"} in confirmations["parameters"]
    assert confirmations["post"]["operationId"] == "confirmEntityRelationSource"
    assert confirmations["get"]["operationId"] == "readEntityRelationConfirmations"
    assert confirmations["post"]["requestBody"]["content"]["application/json"]["schema"]["$ref"].endswith("entity-relation-confirmation-command.schema.json")
    assert confirmations["get"]["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith("entity-relation-confirmation-view.schema.json")
    assert set(confirmations["post"]["responses"]) == {"200", "400", "401", "403", "404", "409", "503"}
    assert set(confirmations["get"]["responses"]) == {"200", "400", "401", "403", "404", "503"}
    withdraw = api["paths"]["/api/v1/entities/{entityId}/relations/{relationId}/confirmations/withdraw"]
    assert {"$ref": "#/components/parameters/clientRequestId"} in withdraw["parameters"]
    assert withdraw["post"]["operationId"] == "withdrawEntityRelationSourceConfirmation"
    assert withdraw["post"]["requestBody"]["content"]["application/json"]["schema"]["$ref"].endswith("entity-relation-confirmation-withdraw-command.schema.json")
    assert set(withdraw["post"]["responses"]) == {"200", "400", "401", "403", "404", "409", "503"}
    text = (ROOT / "contracts/openapi/platform-draft.yaml").read_text(encoding="utf-8")
    assert "RELATION_CONFIRMATION_CONFLICT" in text
