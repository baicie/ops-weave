import json
from pathlib import Path
import pytest
from jsonschema import ValidationError
from test_pipeline import validate

ROOT = Path(__file__).resolve().parents[2]
def example(name):
    return json.loads((ROOT / f'contracts/examples/{name}.json').read_text())

@pytest.mark.parametrize('field', ['tenantId','sourceInstanceId','namespace','entityId','permissions','fence','sql'])
def test_snapshot_cannot_override_trusted_context(field):
    body = example('source-snapshot-input')
    body[field] = 'attacker'
    with pytest.raises(ValidationError):
        validate('source-snapshot-input',body)

@pytest.mark.parametrize('field,value', [('assetUuid','Default string'),('assetUuid','00000000-0000-0000-0000-000000000000'),('externalId','x\n'),('values',{}),('values',{'owner':'x','tenantId':'other'})])
def test_snapshot_record_has_only_scoped_strong_identity_and_bounded_fields(field,value):
    body = example('source-snapshot-input')
    body['input']['records'][0][field] = value
    with pytest.raises(ValidationError):
        validate('source-snapshot-input',body)

@pytest.mark.parametrize('field,value', [('requestId','bad'),('observedAt','yesterday'),('complete','true')])
def test_snapshot_input_types(field,value):
    body = example('source-snapshot-input')
    body['input'][field] = value
    with pytest.raises(ValidationError):
        validate('source-snapshot-input',body)

def test_snapshot_size_is_bounded():
    body = example('source-snapshot-input')
    body['input']['records'] *= 101
    with pytest.raises(ValidationError):
        validate('source-snapshot-input',body)

@pytest.mark.parametrize('field,value', [('dataMode','live'),('engine','arbitrary-code'),('markedAbsent',101),('mappingDigest','sha256:'+'a'*64+'\n')])
def test_receipt_rejects_false_claims(field,value):
    body=example('source-snapshot-receipt')
    body[field]=value
    with pytest.raises(ValidationError):
        validate('source-snapshot-receipt',body)

@pytest.mark.parametrize('status',['PRESENT','ABSENT','STALE','IDENTITY_REVOKED'])
def test_presence_status_is_explicit(status):
    body=example('source-presence-page')
    body['items'][0]['status']=status
    validate('source-presence-page',body)
    body['items'][0]['status']='CONNECTED'
    with pytest.raises(ValidationError):
        validate('source-presence-page',body)
