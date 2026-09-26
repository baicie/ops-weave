import json
from pathlib import Path
import pytest
from jsonschema import ValidationError
from test_pipeline import validate

ROOT = Path(__file__).resolve().parents[2]

def example():
    return json.loads((ROOT / 'contracts/examples/host-sync-failure.json').read_text())

@pytest.mark.parametrize('field,value', [
    ('fence', 1), ('tenantId', 'other'), ('leaseUntil', '2099-01-01T00:00:00Z'),
    ('snapshotComplete', True), ('pages', -1), ('accepted', 5000001),
    ('failureCode', 'SOURCE_SCAN_BUSY\n'), ('syncRunId', 'not-a-uuid'),
    ('scanConsistency', 'consistent-snapshot'), ('summary', 'x'*257),
])
def test_host_failure_rejects_unsafe_or_invalid_fields(field, value):
    body = example()
    body[field] = value
    with pytest.raises(ValidationError):
        validate('host-sync-failure', body)

@pytest.mark.parametrize('code', ['SOURCE_SCAN_BUSY','SOURCE_SCAN_LOST','SOURCE_SCAN_DEADLINE','SOURCE_SCAN_LIMIT','SOURCE_SCAN_UNVERIFIED'])
def test_fenced_failure_needs_run_and_summary(code):
    body = example()
    body['failureCode'] = code
    validate('host-sync-failure', body)
    del body['syncRunId']
    with pytest.raises(ValidationError):
        validate('host-sync-failure', body)

@pytest.mark.parametrize('label', ['offset-scan-attempt','hostid-watermark-snapshot','itemid-watermark-snapshot'])
def test_a_failed_walk_states_how_it_was_bounded(label):
    body = example()
    body['scanConsistency'] = label
    validate('host-sync-failure', body)

def test_closed_source_has_no_fabricated_run():
    body = {key: value for key,value in example().items() if key not in ['syncRunId','failureCode','summary','scanConsistency']}
    validate('host-sync-failure', body)
