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


def relation():
    return {
        "schemaVersion": "1.0",
        "id": "22222222-2222-4222-8222-222222222222",
        "tenantId": "tenant-a",
        "fromEntityId": "33333333-3333-4333-8333-333333333333",
        "toEntityId": "44444444-4444-4444-8444-444444444444",
        "relationType": "builtin.depends_on",
        "relationRevision": 1,
        "validFrom": "2026-10-10T00:00:00Z",
        "validTo": None,
        "dataMode": "unknown",
        "version": 1,
    }


def correction_command():
    return {
        "requestId": "11111111-1111-4111-8111-111111111111",
        "relationId": "22222222-2222-4222-8222-222222222222",
        "reason": "端点录入错误",
        "expectedVersion": 1,
        "fromEntityId": "33333333-3333-4333-8333-333333333333",
        "toEntityId": "55555555-5555-4555-8555-555555555555",
        "relationType": "builtin.depends_on",
        "relationRevision": 1,
    }


def test_correction_command_is_closed():
    validate("entity-relation-correction-command", correction_command())


@pytest.mark.parametrize("patch", [
    {"tenantId": "tenant-a"}, {"actor": "operator"}, {"reason": ""}, {"reason": "bad\nreason"},
    {"reason": "x" * 257}, {"expectedVersion": 0}, {"expectedVersion": None},
    {"fromEntityId": "not-a-uuid"}, {"toEntityId": None}, {"relationType": "javascript"},
    {"relationRevision": 0}, {"relationRevision": 10001}, {"requestId": "x"},
    {"sourceRef": "operator"}, {"dataMode": "unknown"}, {"unexpected": True},
])
def test_correction_command_rejects_tenant_actor_and_invalid_fields(patch):
    value = correction_command()
    value.update(patch)
    with pytest.raises(ValidationError):
        validate("entity-relation-correction-command", value)


def test_correction_receipt_is_closed():
    validate("entity-relation-correction-receipt", {
        "schemaVersion": "1.0", "requestId": correction_command()["requestId"], "replayed": False,
        "previousRelationId": correction_command()["relationId"], "relation": relation(),
    })
    validate("entity-relation-correction-receipt", {
        "schemaVersion": "1.0", "requestId": correction_command()["requestId"], "replayed": True,
        "previousRelationId": correction_command()["relationId"], "relation": relation(),
    })


@pytest.mark.parametrize("patch", [
    {"schemaVersion": "2.0"}, {"replayed": "yes"}, {"previousRelationId": "x"}, {"relation": None},
    {"tenantId": "tenant-a"},
])
def test_correction_receipt_rejects_invalid_fields(patch):
    value = {
        "schemaVersion": "1.0", "requestId": correction_command()["requestId"], "replayed": False,
        "previousRelationId": correction_command()["relationId"], "relation": relation(),
    }
    value.update(patch)
    with pytest.raises(ValidationError):
        validate("entity-relation-correction-receipt", value)


def correction_record():
    return {
        "schemaVersion": "1.0", "tenantId": "tenant-a",
        "requestId": correction_command()["requestId"],
        "previousRelationId": correction_command()["relationId"],
        "relationId": relation()["id"],
        "reason": "端点录入错误", "expectedVersion": 1,
        "correctedAt": "2026-10-10T00:00:00Z",
    }


def test_correction_record_and_page_are_closed():
    validate("entity-relation-correction-record", correction_record())
    validate("entity-relation-correction-page", {
        "schemaVersion": "1.0", "storage": "postgres", "tenantId": "tenant-a",
        "entityId": relation()["fromEntityId"], "items": [correction_record()], "nextCursor": None,
    })


@pytest.mark.parametrize("patch", [
    {"reason": "x" * 257}, {"reason": ""}, {"expectedVersion": 0}, {"correctedAt": "not-a-time"},
    {"previousRelationId": "x"}, {"requestId": None}, {"extra": True},
])
def test_correction_record_rejects_invalid_fields(patch):
    value = correction_record()
    value.update(patch)
    with pytest.raises(ValidationError):
        validate("entity-relation-correction-record", value)


def test_correction_page_bounds_items_and_rejects_extra_fields():
    page = {
        "schemaVersion": "1.0", "storage": "memory", "tenantId": "tenant-a",
        "entityId": relation()["fromEntityId"], "items": [correction_record()] * 51, "nextCursor": None,
    }
    with pytest.raises(ValidationError):
        validate("entity-relation-correction-page", page)
    page["items"] = [correction_record()]
    page["nextCursor"] = "not-a-uuid"
    with pytest.raises(ValidationError):
        validate("entity-relation-correction-page", page)


def test_correction_openapi_is_registered_with_closed_errors():
    api = yaml.safe_load((ROOT / "contracts/openapi/platform-draft.yaml").read_text(encoding="utf-8"))
    correct = api["paths"]["/api/v1/entities/{entityId}/relations/{relationId}/correct"]
    assert {"$ref": "#/components/parameters/clientRequestId"} in correct["parameters"]
    assert correct["post"]["operationId"] == "correctEntityRelation"
    assert correct["post"]["requestBody"]["content"]["application/json"]["schema"]["$ref"].endswith("entity-relation-correction-command.schema.json")
    assert correct["post"]["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith("entity-relation-correction-receipt.schema.json")
    assert set(correct["post"]["responses"]) == {"200", "400", "401", "403", "404", "409", "503"}
    page = api["paths"]["/api/v1/entities/{entityId}/relations/corrections"]
    assert {"$ref": "#/components/parameters/clientRequestId"} in page["parameters"]
    assert page["get"]["operationId"] == "pageEntityRelationCorrections"
    assert page["get"]["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith("entity-relation-correction-page.schema.json")
    assert set(page["get"]["responses"]) == {"200", "400", "401", "403", "404", "503"}
    text = (ROOT / "contracts/openapi/platform-draft.yaml").read_text(encoding="utf-8")
    assert "RELATION_CORRECTION_CONFLICT" in text
    assert "tenant or actor" in text
