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
