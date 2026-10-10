import json
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator, FormatChecker, ValidationError
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / "contracts" / "schemas" / "v1"
REGISTRY = Registry().with_resources((p.as_uri(), Resource.from_contents(json.loads(p.read_text(encoding="utf-8"))))
    for p in SCHEMAS.glob("*.schema.json"))


def validate(name, value):
    schema = SCHEMAS / f"{name}.schema.json"
    Draft202012Validator({"$ref": schema.as_uri()}, registry=REGISTRY, format_checker=FormatChecker()).validate(value)


def relation():
    return {
        "schemaVersion": "1.0",
        "id": "11111111-1111-4111-8111-111111111111",
        "tenantId": "tenant-a",
        "fromEntityId": "22222222-2222-4222-8222-222222222222",
        "toEntityId": "33333333-3333-4333-8333-333333333333",
        "relationType": "builtin.depends_on",
        "relationRevision": 1,
        "validFrom": "2026-10-06T00:00:00Z",
        "validTo": None,
        "dataMode": "unknown",
        "version": 1,
    }


def test_relation_page_and_receipt_are_closed():
    item = relation()
    validate("entity-relation", item)
    validate("entity-relation-page", {
        "schemaVersion": "1.0", "storage": "memory", "tenantId": "tenant-a",
        "entityId": item["fromEntityId"], "asOf": item["validFrom"],
        "items": [item], "nextCursor": None,
    })
    validate("entity-relation-receipt", {
        "schemaVersion": "1.0", "requestId": item["id"], "replayed": False, "relation": item,
    })


@pytest.mark.parametrize("patch", [
    {"sourceRef": "secret"}, {"fromEntityId": "bad"}, {"relationType": "javascript"},
    {"dataMode": "real"}, {"version": 0},
])
def test_relation_response_does_not_leak_or_accept_invalid_fields(patch):
    value = relation()
    value.update(patch)
    with pytest.raises(ValidationError):
        validate("entity-relation", value)


def history_entry():
    closed = relation()
    closed["id"] = "44444444-4444-4444-8444-444444444444"
    closed["validTo"] = "2026-10-07T00:00:00Z"
    return {"relation": closed, "closeReason": "接口下线确认"}


def close_command():
    return {
        "requestId": "55555555-5555-4555-8555-555555555555",
        "relationId": "11111111-1111-4111-8111-111111111111",
        "reason": "接口下线确认",
        "validTo": "2026-10-07T00:00:00Z",
        "expectedVersion": 1,
    }


def test_close_command_receipt_and_history_are_closed():
    validate("entity-relation-close-command", close_command())
    minimal = close_command()
    del minimal["validTo"]
    validate("entity-relation-close-command", minimal)
    validate("entity-relation-history-entry", {"relation": relation(), "closeReason": None})
    validate("entity-relation-history-entry", history_entry())
    validate("entity-relation-history-page", {
        "schemaVersion": "1.0", "storage": "postgres", "tenantId": "tenant-a",
        "entityId": relation()["fromEntityId"],
        "items": [history_entry(), {"relation": relation(), "closeReason": None}], "nextCursor": None,
    })
    validate("entity-relation-receipt", {
        "schemaVersion": "1.0", "requestId": close_command()["requestId"], "replayed": True, "relation": history_entry()["relation"],
    })


@pytest.mark.parametrize("patch", [
    {"reason": ""}, {"reason": "bad\nreason"}, {"reason": "x" * 257},
    {"expectedVersion": 0}, {"expectedVersion": None}, {"sourceRef": "operator"}, {"schemaVersion": "1.0"},
])
def test_close_command_rejects_invalid_fields(patch):
    value = close_command()
    value.update(patch)
    with pytest.raises(ValidationError):
        validate("entity-relation-close-command", value)


@pytest.mark.parametrize("patch", [{"closeReason": "x" * 257}, {"unexpected": True}])
def test_history_entry_rejects_invalid_fields(patch):
    value = history_entry()
    value.update(patch)
    with pytest.raises(ValidationError):
        validate("entity-relation-history-entry", value)


def test_history_entry_reason_requires_an_end_time():
    value = history_entry()
    value["relation"]["validTo"] = None
    with pytest.raises(ValidationError):
        validate("entity-relation-history-entry", value)


def test_history_page_bounds_items_and_rejects_extra_fields():
    page = {
        "schemaVersion": "1.0", "storage": "memory", "tenantId": "tenant-a",
        "entityId": relation()["fromEntityId"], "items": [history_entry()] * 51, "nextCursor": None,
    }
    with pytest.raises(ValidationError):
        validate("entity-relation-history-page", page)
    page["items"] = [history_entry()]
    page["closeReason"] = None
    with pytest.raises(ValidationError):
        validate("entity-relation-history-page", page)
