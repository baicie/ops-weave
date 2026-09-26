import copy
import json
from pathlib import Path
import pytest
from jsonschema import Draft202012Validator, FormatChecker, ValidationError
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / 'contracts/schemas/v1'
REGISTRY = Registry().with_resources((p.name, Resource.from_contents(json.loads(p.read_text()))) for p in SCHEMAS.glob('*.json'))

def validate(name, value):
    Draft202012Validator(json.loads((SCHEMAS / f'{name}.schema.json').read_text()), registry=REGISTRY, format_checker=FormatChecker()).validate(value)

@pytest.mark.parametrize('field,value', [
    ('recoveredAt', None), ('recoveryEventId', None), ('state', 'ACTIVE'), ('gaps', ['RECOVERY_EVENT_UNAVAILABLE']),
    ('problemEventId', '01'), ('problemEventId', '1' * 21), ('severity', 6), ('title', 'x' * 301), ('title', 'x\ny'),
    ('hostIds', ['10084', '10084']), ('hostIds', [str(i + 1) for i in range(21)]), ('observedAt', 'yesterday'), ('permissions', ['admin'])
])
def test_rejects_invalid_problem(field, value):
    sample = json.loads((ROOT / 'contracts/examples/external-problem.json').read_text())
    sample[field] = value
    with pytest.raises(ValidationError): validate('external-problem', sample)

def test_missing_recovery_must_remain_explicit():
    sample = json.loads((ROOT / 'contracts/examples/external-problem.json').read_text())
    sample.update(state='RECOVERY_UNKNOWN', recoveredAt=None, gaps=['RECOVERY_EVENT_UNAVAILABLE'])
    validate('external-problem', sample)
    sample['gaps'] = []
    with pytest.raises(ValidationError): validate('external-problem', sample)

def test_page_does_not_claim_persistence_or_allow_unbounded_data():
    sample = json.loads((ROOT / 'contracts/examples/external-problem-page.json').read_text())
    for field, value in [('storage', 'postgres'), ('items', sample['items'] * 101), ('dataMode', 'auto')]:
        broken = copy.deepcopy(sample); broken[field] = value
        with pytest.raises(ValidationError): validate('external-problem-page', broken)
