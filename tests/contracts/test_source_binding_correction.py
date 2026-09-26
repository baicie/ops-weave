import json
from pathlib import Path
import pytest
from jsonschema import ValidationError
from test_pipeline import validate

ROOT = Path(__file__).resolve().parents[2]
def sample(name):
    return json.loads((ROOT / f'contracts/examples/{name}.json').read_text())

@pytest.mark.parametrize('field',['tenantId','actor','sourceInstanceId','namespace','permissions','fence'])
def test_correction_does_not_override_trusted_authority(field):
    value=sample('source-binding-correction-input');value[field]='override'
    with pytest.raises(ValidationError): validate('source-binding-correction-input',value)

@pytest.mark.parametrize('field,value',[('expectedPreviousVersion',0),('expectedTargetVersion',9007199254740991),('previousEntityId','bad'),('expectedSnapshotId','bad'),('targetEntityId','bad'),('reason',''),('reason','x\n'),('values',{}),('values',{'tenantId':'other'}),('observedAt','yesterday')])
def test_correction_requires_exact_versions_reason_and_new_observation(field,value):
    body=sample('source-binding-correction-input');body['command'][field]=value
    with pytest.raises(ValidationError): validate('source-binding-correction-input',body)

@pytest.mark.parametrize('field,value',[('dataMode','live'),('previousEntityVersionAfter',1),('storage','memory')])
def test_correction_receipt_never_claims_a_live_vendor_or_unversioned_write(field,value):
    body=sample('source-binding-correction-receipt');body[field]=value
    with pytest.raises(ValidationError): validate('source-binding-correction-receipt',body)

def test_correction_history_has_a_bounded_page():
    body=sample('source-binding-correction-page');body['items']*=26
    with pytest.raises(ValidationError): validate('source-binding-correction-page',body)
