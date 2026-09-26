import json
from pathlib import Path
import pytest
from jsonschema import ValidationError
from test_pipeline import validate

ROOT = Path(__file__).resolve().parents[2]
def sample(name):
    return json.loads((ROOT / f'contracts/examples/{name}.json').read_text())

@pytest.mark.parametrize('name',['source-connection-check','source-connection-check-receipt','source-connection-check-page'])
def test_published_examples_conform(name):
    validate(name,sample(name))

@pytest.mark.parametrize('field',['permissions','token','endpoint','vendorVersion','reachableSince'])
def test_a_check_never_echoes_client_authority_or_unknown_fields(field):
    body=sample('source-connection-check');body[field]='override'
    with pytest.raises(ValidationError):validate('source-connection-check',body)

@pytest.mark.parametrize('field,value',[('dataMode','live'),('dataMode','connection-check'),('statusCode','unreachable\n'),('statusCode','x'*65),('reportedVersion','x'*33),('reachable','true'),('checkedAt','yesterday')])
def test_a_check_stays_inside_the_bounded_shape(field,value):
    body=sample('source-connection-check');body[field]=value
    with pytest.raises(ValidationError):validate('source-connection-check',body)

def test_an_unreachable_check_never_reports_a_version():
    body=sample('source-connection-check')
    body['reachable']=False;body['statusCode']='unreachable'
    validate('source-connection-check',body)
    body['reportedVersion']='7.0.0'
    with pytest.raises(ValidationError):validate('source-connection-check',body)

@pytest.mark.parametrize('field,value',[('dataMode','scan-log'),('storage','victim-store'),('limit',0),('limit',51),('items','not-an-array')])
def test_the_check_page_stays_bounded_and_never_claims_another_mode(field,value):
    body=sample('source-connection-check-page');body[field]=value
    with pytest.raises(ValidationError):validate('source-connection-check-page',body)

def test_the_check_page_caps_items_at_the_documented_bound():
    body=sample('source-connection-check-page');body['items']=body['items']*26
    with pytest.raises(ValidationError):validate('source-connection-check-page',body)

def test_the_receipt_requires_exactly_one_check():
    body=sample('source-connection-check-receipt');del body['check']
    with pytest.raises(ValidationError):validate('source-connection-check-receipt',body)
    body=sample('source-connection-check-receipt');body['check']['reachable']=False;body['check']['reportedVersion']='7.0.0'
    with pytest.raises(ValidationError):validate('source-connection-check-receipt',body)
