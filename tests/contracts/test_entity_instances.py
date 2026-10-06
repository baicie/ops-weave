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


def command():
    return {
        "requestId": "11111111-1111-4111-8111-111111111111",
        "entityId": "22222222-2222-4222-8222-222222222222",
        "model": {"id": "builtin.host", "revision": 1},
        "name": "edge-01",
        "lifecycle": "ACTIVE",
        "attributes": {"hostname": "edge-01", "address": "10.0.0.1"},
    }


def test_entity_instance_command_and_receipt_are_closed():
    validate("entity-instance-command", command())
    validate("entity-instance-receipt", {
        "schemaVersion": "1.0", "storage": "memory", "replayed": False,
        "tenantId": "tenant-a",
        "model": {"id": "builtin.host", "revision": 1, "digest": "sha256:" + "a" * 64},
        "entity": {
            "schemaVersion": "1.0", "id": command()["entityId"], "tenantId": "tenant-a",
            "entityType": "Host", "name": "edge-01", "lifecycle": "ACTIVE", "version": 1,
            "model": {"id": "builtin.host", "revision": 1, "digest": "sha256:" + "a" * 64},
            "attributes": {"hostname": "edge-01"},
        },
    })


def test_legacy_entity_without_model_pin_remains_valid():
    validate("entity", {
        "schemaVersion": "1.0", "id": command()["entityId"], "tenantId": "tenant-a",
        "entityType": "Host", "name": "edge-01", "lifecycle": "ACTIVE", "version": 1,
        "attributes": {"hostname": "edge-01"},
    })


@pytest.mark.parametrize("pin", [
    {"id": "custom.checkout", "revision": 1, "digest": "sha256:" + "a" * 64, "tenantId": "untrusted"},
    {"id": "custom.checkout", "revision": 0, "digest": "sha256:" + "a" * 64},
    {"id": "custom.checkout", "revision": 1, "digest": "sha256:invalid"},
])
def test_entity_model_pin_rejects_unbounded_or_malformed_values(pin):
    value = {
        "schemaVersion": "1.0", "id": command()["entityId"], "tenantId": "tenant-a",
        "entityType": "custom.checkout", "name": "checkout", "lifecycle": "ACTIVE", "version": 1,
        "model": pin, "attributes": {},
    }
    with pytest.raises(ValidationError):
        validate("entity", value)


@pytest.mark.parametrize("patch", [
    {"tenantId": "attacker"}, {"sourceRef": "source"}, {"model": {"id": "javascript", "revision": 1}},
    {"attributes": {"nested": {"value": 1}}}, {"expectedVersion": 0},
])
def test_entity_instance_command_rejects_untrusted_or_unbounded_fields(patch):
    value = command()
    value.update(patch)
    with pytest.raises(ValidationError):
        validate("entity-instance-command", value)
