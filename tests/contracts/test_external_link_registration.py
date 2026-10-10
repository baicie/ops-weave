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


def link():
    return {
        "namespace": "workflow.demo",
        "objectType": "entity",
        "externalKey": "host-1",
        "generation": "builtin.host",
        "targetId": "22222222-2222-4222-8222-222222222222",
    }


def registration():
    return {
        "requestId": "11111111-1111-4111-8111-111111111111",
        "namespace": "workflow.demo",
        "objectType": "entity",
        "externalKey": "host-1",
        "targetId": "22222222-2222-4222-8222-222222222222",
    }


def test_registration_command_is_closed():
    validate("external-link-registration", registration())
    full = registration()
    full["generation"] = "builtin.host"
    full["reason"] = "来源身份确认"
    validate("external-link-registration", full)


@pytest.mark.parametrize("patch", [
    {"tenantId": "tenant-a"}, {"actor": "operator"}, {"namespace": "Workflow.Bogus"},
    {"objectType": "Entity Type"}, {"externalKey": " "}, {"externalKey": "x" * 513},
    {"generation": " "}, {"reason": "x" * 257}, {"targetId": "not-a-uuid"},
    {"requestId": "not-a-uuid"}, {"unexpected": True},
])
def test_registration_command_rejects_tenant_actor_and_invalid_fields(patch):
    value = registration()
    value.update(patch)
    with pytest.raises(ValidationError):
        validate("external-link-registration", value)


def test_link_and_receipt_are_closed():
    validate("external-link", link())
    validate("external-link-registration-receipt", {
        "schemaVersion": "1.0", "requestId": registration()["requestId"], "replayed": False, "link": link(),
    })
    validate("external-link-registration-receipt", {
        "schemaVersion": "1.0", "requestId": registration()["requestId"], "replayed": True, "link": link(),
    })


@pytest.mark.parametrize("patch", [
    {"schemaVersion": "2.0"}, {"replayed": "yes"}, {"link": None},
])
def test_receipt_rejects_invalid_fields(patch):
    value = {"schemaVersion": "1.0", "requestId": registration()["requestId"], "replayed": False, "link": link()}
    value.update(patch)
    with pytest.raises(ValidationError):
        validate("external-link-registration-receipt", value)


@pytest.mark.parametrize("patch", [{"namespace": "Bad Namespace"}, {"targetId": "x"}, {"extra": 1}])
def test_link_rejects_invalid_fields(patch):
    value = link()
    value.update(patch)
    with pytest.raises(ValidationError):
        validate("external-link", value)


def entry():
    return {"link": link(), "reason": "来源身份确认", "registeredAt": "2026-10-10T00:00:00Z"}


def test_entry_and_page_are_closed():
    validate("external-link-entry", entry())
    null_reason = entry()
    null_reason["reason"] = None
    validate("external-link-entry", null_reason)
    validate("external-link-page", {
        "schemaVersion": "1.0", "storage": "postgres", "tenantId": "tenant-a",
        "items": [entry()], "nextCursor": None,
    })


def test_page_bounds_items_and_rejects_extra_fields():
    page = {
        "schemaVersion": "1.0", "storage": "memory", "tenantId": "tenant-a",
        "items": [entry()] * 51, "nextCursor": None,
    }
    with pytest.raises(ValidationError):
        validate("external-link-page", page)
    page["items"] = [entry()]
    page["unexpected"] = True
    with pytest.raises(ValidationError):
        validate("external-link-page", page)


@pytest.mark.parametrize("patch", [{"reason": "x" * 257}, {"registeredAt": "not-a-time"}, {"extra": True}])
def test_entry_rejects_invalid_fields(patch):
    value = entry()
    value.update(patch)
    with pytest.raises(ValidationError):
        validate("external-link-entry", value)
