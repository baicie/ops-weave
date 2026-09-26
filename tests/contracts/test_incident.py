import copy
import json
from pathlib import Path
import pytest
from jsonschema import Draft202012Validator, FormatChecker, ValidationError
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = Registry().with_resources((p.as_uri(), Resource.from_contents(json.loads(p.read_text()))) for p in (ROOT / 'contracts/schemas/v1').glob('*.json'))
def sample(name): return json.loads((ROOT / f'contracts/examples/{name}.json').read_text())
def validate(name, body):
    definition = ROOT / f'contracts/schemas/v1/{name}.schema.json'
    Draft202012Validator({'$ref': definition.as_uri()}, registry=REGISTRY, format_checker=FormatChecker()).validate(body)

@pytest.mark.parametrize('name,field,value', [
    ('problem-ingest-request', 'tenantId', 'other'), ('problem-ingest-request', 'from', -1), ('problem-ingest-request', 'limit', 101),
    ('problem-ingest-request', 'afterEventId', '01'), ('problem-ingest-result', 'storage', 'not-persisted'),
    ('incident-transition-request', 'expectedVersion', 0), ('incident-transition-request', 'expectedVersion', 18446744073709551617),
    ('incident-transition-request', 'target', 'ADMIN'), ('incident-transition-request', 'actor', 'forged'),
    ('incident-page', 'items', [sample('incident')] * 101), ('incident-detail', 'storage', 'fallback')
])
def test_invalid_incident_boundary(name, field, value):
    body = sample(name); body[field] = value
    with pytest.raises(ValidationError): validate(name, body)

def test_timeline_cannot_invent_recovery_or_drop_protected_gaps():
    record = sample('incident-record')
    for change in ('recovery', 'actor', 'gaps', 'source_contract', 'mode'):
        broken = copy.deepcopy(record)
        if change == 'recovery': broken['timeline'][1]['recoveryEventId'] = None
        elif change == 'actor': broken['timeline'][0]['actor'] = 'forged'
        elif change == 'gaps': broken['gaps'] = []
        elif change == 'source_contract': broken['problems'][0]['sourceContract'] = 'latest'
        else: broken['problems'][0]['dataMode'] = 'auto'
        with pytest.raises(ValidationError): validate('incident-record', broken)
