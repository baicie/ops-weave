import json
import pytest
from jsonschema import ValidationError
from test_entity_page import ROOT, validate

def sample():
    return json.loads((ROOT / 'contracts/examples/history-service-grants.json').read_text())

@pytest.mark.parametrize('key,value', [
    ('issuer', 'http://remote.invalid'), ('clientId', ''), ('clientId', 'worker\n'), ('externalSubject', '\n'), ('tenantId', '../other'), ('revision', 0),
    ('permissions', ['shell']), ('scope', {'tenantWide': True}), ('clientSecret', 'never-in-grants'), ('enabled', 'true'),
    ('sourceInstanceId', '*'), ('itemIds', []), ('itemIds', ['0']), ('itemIds', ['20001', '20001']), ('itemIds', ['x'] * 101),
    ('entityIds', []), ('entityIds', ['*']), ('entityIds', ['00000000-0000-4000-8000-000000000001'] * 2),
    ('metricKeys', []), ('metricKeys', ['*']), ('metricKeys', ['host.cpu\n']), ('validFrom', 'yesterday'), ('validUntil', '2026-02-30T00:00:00Z'),
    ('from', -1), ('till', 10000000000), ('maxWindowSeconds', 0), ('maxWindowSeconds', 3601), ('maxPoints', 0), ('maxPoints', 501),
    ('requestsPerMinute', 0), ('requestsPerMinute', 121),
])
def test_service_grant_rejects_identity_and_budget_expansion(key, value):
    document = sample(); document['grants'][0][key] = value
    with pytest.raises(ValidationError): validate('history-service-grants', document)

def test_service_grants_are_closed_and_bounded():
    document = sample(); document['extra'] = True
    with pytest.raises(ValidationError): validate('history-service-grants', document)
    document = sample(); document['grants'] *= 501
    with pytest.raises(ValidationError): validate('history-service-grants', document)
    validate('history-service-grants', {'schemaVersion': '1.0', 'grants': []})

def test_service_read_has_its_own_security_scheme():
    import yaml
    api = yaml.safe_load((ROOT / 'contracts/openapi/platform-draft.yaml').read_text())
    service = api['paths']['/api/v1/service/ingestion/items/{itemId}/history']['get']
    assert service['security'] == [{'historyService': []}]
    assert service['responses']['200']['content']['application/json']['schema']['$ref'].endswith('/metric-history-page.schema.json')
