import json
import pytest
import yaml
from jsonschema import ValidationError
from test_entity_page import ROOT, validate


def sample(name):
    return json.loads((ROOT / f"contracts/examples/{name}.json").read_text())


@pytest.mark.parametrize("field,value", [
    ("value", "Default string"), ("value", "10.0.0.1"),
    ("value", "00000000-0000-0000-0000-000000000000"),
    ("value", "ffffffff-ffff-ffff-ffff-ffffffffffff"),
    ("value", "ABCD1234-5678-4234-ABCD-123456789ABC"),
    ("expectedEntityVersion", 0), ("expectedEntityVersion", 9007199254740991), ("expectedEntityVersion", 9007199254740992),
    ("expectedNamespace", ""), ("expectedNamespace", "../assets"),
    ("expectedNamespace", "enterprise-assets\n"),
    ("tenantId", "other"), ("actor", "forged"), ("namespace", "untrusted"),
    ("permissions", ["entity.manage"]), ("reason", ""), ("reason", "x\ny"),
    ("reason", "x" * 501),
])
def test_registration_rejects_weak_identity_and_trust_overrides(field, value):
    item = sample("asset-identity-claim")
    item[field] = value
    with pytest.raises(ValidationError): validate("asset-identity-claim", item)


@pytest.mark.parametrize("name", ["asset-identity-claim", "asset-identity-revoke"])
def test_writes_must_pin_the_reviewed_server_namespace(name):
    item = sample(name)
    del item["expectedNamespace"]
    with pytest.raises(ValidationError): validate(name, item)


@pytest.mark.parametrize("field,value", [("version", 2), ("status", "REVOKED"),
    ("verification", "model-confirmed"), ("kind", "ip"), ("assertedAt", "2026-02-30T00:00:00Z")])
def test_active_identity_requires_consistent_audit_state(field, value):
    item = sample("asset-identity")
    item[field] = value
    with pytest.raises(ValidationError): validate("asset-identity", item)


def test_revoked_record_is_historical_and_cannot_resolve():
    item = sample("asset-identity")
    item.update(status="REVOKED", version=2, revocation={
        "requestId": item["id"], "actor": "operator", "reason": "Corrected register", "at": item["assertedAt"]})
    validate("asset-identity", item)
    resolution = sample("asset-identity-resolution")
    resolution["identity"] = item
    with pytest.raises(ValidationError): validate("asset-identity-resolution", resolution)
    item["revocation"]["actor"] = ""
    with pytest.raises(ValidationError): validate("asset-identity", item)


@pytest.mark.parametrize("name", ["source-review", "source-review-import"])
def test_optional_identity_pin_is_closed_and_cannot_accept_revoked_version(name):
    item = sample(name)
    validate(name, item)
    item["identity"] = sample("asset-identity-pin")
    validate(name, item)
    item["identity"]["version"] = 2
    with pytest.raises(ValidationError): validate(name, item)
    item["identity"] = {**sample("asset-identity-pin"), "tenantId": "other"}
    with pytest.raises(ValidationError): validate(name, item)


def test_lookup_and_revocation_cannot_override_target_or_identity():
    for name, field in [("asset-identity-resolve-request", "entityId"), ("asset-identity-resolve-request", "namespace"),
                        ("asset-identity-revoke", "value"), ("asset-identity-revoke", "actor")]:
        item = sample(name)
        item[field] = "injected"
        with pytest.raises(ValidationError): validate(name, item)


def test_registry_page_is_bounded_and_receipt_action_matches_record():
    page = sample("asset-identity-page")
    page["items"] *= 26
    with pytest.raises(ValidationError): validate("asset-identity-page", page)
    receipt = sample("asset-identity-receipt")
    receipt["action"] = "REVOKE"
    with pytest.raises(ValidationError): validate("asset-identity-receipt", receipt)


def test_registry_routes_accept_only_human_sessions():
    api = yaml.safe_load((ROOT / 'contracts/openapi/platform-draft.yaml').read_text())
    expected = {'get': 'asset-identity-page', 'post': 'asset-identity-receipt'}
    route = api['paths']['/api/v1/entities/{entityId}/identity-keys']
    for method, schema in expected.items():
        assert route[method]['security'] == [{'bearerAuth': []}, {'browserSession': []}]
        assert route[method]['responses']['200']['content']['application/json']['schema']['$ref'].endswith(schema + '.schema.json')
    for path in ['/api/v1/inventory/resolve-identity', '/api/v1/entities/{entityId}/identity-keys/{identityId}/revocations']:
        assert api['paths'][path]['post']['security'] == [{'bearerAuth': []}, {'browserSession': []}]
