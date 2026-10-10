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


def command():
    return {
        "requestId": "33333333-3333-4333-8333-333333333333",
        "entityType": "Host",
        "model": {"id": "builtin.host", "revision": 1},
        "maxUpdates": 100,
    }


def counts():
    return {"scanned": 3, "updated": 2, "keptWithoutPin": 1, "skippedPinned": 4, "skippedSource": 1}


def receipt():
    return {
        "schemaVersion": "1.0", "storage": "postgres", "replayed": False, "tenantId": "tenant-a",
        "requestId": command()["requestId"],
        "request": {"entityType": "Host", "model": {"id": "builtin.host", "revision": 1, "digest": "sha256:" + "a" * 64}},
        "counts": counts(),
        "completedAt": "2026-10-10T00:00:00Z",
    }


def test_backfill_command_is_closed():
    validate("entity-model-pin-backfill-command", command())


@pytest.mark.parametrize("patch", [
    {"tenantId": "tenant-a"}, {"actor": "operator"}, {"requestId": "not-a-uuid"},
    {"entityType": ""}, {"entityType": "Host Type"}, {"entityType": "1host"}, {"entityType": "x" * 65},
    {"model": {"id": "builtin/../../escape", "revision": 1}}, {"model": {"id": "javascript", "revision": 1}},
    {"model": {"id": "builtin.host", "revision": 0}}, {"model": {"id": "builtin.host", "revision": 10001}},
    {"model": {"id": "builtin.host", "revision": 1, "digest": "sha256:" + "a" * 64}},
    {"maxUpdates": 0}, {"maxUpdates": 501}, {"maxUpdates": 1.5}, {"maxUpdates": "100"},
    {"unexpected": True},
])
def test_backfill_command_rejects_tenant_actor_and_invalid_fields(patch):
    value = command()
    value.update(patch)
    with pytest.raises(ValidationError):
        validate("entity-model-pin-backfill-command", value)


def test_backfill_receipt_is_closed_and_counted():
    validate("entity-model-pin-backfill-receipt", receipt())
    replayed = receipt()
    replayed["replayed"] = True
    validate("entity-model-pin-backfill-receipt", replayed)
    bounded = receipt()
    bounded["counts"] = {"scanned": 500, "updated": 500, "keptWithoutPin": 0, "skippedPinned": 0, "skippedSource": 0}
    validate("entity-model-pin-backfill-receipt", bounded)


@pytest.mark.parametrize("patch", [
    {"schemaVersion": "2.0"}, {"replayed": "no"}, {"tenantId": ""},
    {"requestId": "not-a-uuid"}, {"completedAt": "yesterday"}, {"storage": ""},
    {"request": {"entityType": "Host"}}, {"request": {"entityType": "Host", "model": {"id": "builtin.host", "revision": 1}}},
    {"counts": {"scanned": 3, "updated": 2, "keptWithoutPin": 1, "skippedPinned": 4}},
    {"entities": [{"id": "22222222-2222-4222-8222-222222222222"}]},
    {"unexpected": True},
])
def test_backfill_receipt_rejects_invalid_or_open_shapes(patch):
    value = receipt()
    value.update(patch)
    with pytest.raises(ValidationError):
        validate("entity-model-pin-backfill-receipt", value)


@pytest.mark.parametrize("patch", [
    {"scanned": -1}, {"updated": -1}, {"keptWithoutPin": -1}, {"skippedPinned": -1}, {"skippedSource": -1},
    {"scanned": 501}, {"updated": 501}, {"keptWithoutPin": 501}, {"updated": "2"},
])
def test_backfill_counts_are_bounded(patch):
    value = receipt()
    value["counts"] = dict(counts(), **patch)
    with pytest.raises(ValidationError):
        validate("entity-model-pin-backfill-receipt", value)


def test_backfill_openapi_is_registered_with_closed_errors():
    api = yaml.safe_load((ROOT / "contracts/openapi/platform-draft.yaml").read_text(encoding="utf-8"))
    command_path = api["paths"]["/api/v1/entities/model-pins/backfill"]
    assert {"$ref": "#/components/parameters/clientRequestId"} in command_path["parameters"]
    assert command_path["post"]["operationId"] == "backfillEntityModelPins"
    assert command_path["post"]["requestBody"]["content"]["application/json"]["schema"]["$ref"].endswith("entity-model-pin-backfill-command.schema.json")
    assert command_path["post"]["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith("entity-model-pin-backfill-receipt.schema.json")
    assert set(command_path["post"]["responses"]) == {"200", "400", "401", "403", "404", "409", "503"}
    receipt_path = api["paths"]["/api/v1/entities/model-pins/backfill/{requestId}"]
    assert {"$ref": "#/components/parameters/clientRequestId"} in receipt_path["parameters"]
    assert receipt_path["get"]["operationId"] == "getEntityModelPinBackfillReceipt"
    assert receipt_path["get"]["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith("entity-model-pin-backfill-receipt.schema.json")
    assert set(receipt_path["get"]["responses"]) == {"200", "400", "401", "403", "404", "503"}
    text = (ROOT / "contracts/openapi/platform-draft.yaml").read_text(encoding="utf-8")
    assert "never accepts a tenant or actor" in text
    assert "Indeterminable rows keep no pin" in text
