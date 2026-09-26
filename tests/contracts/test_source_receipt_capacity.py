import json
from pathlib import Path
import pytest
from jsonschema import ValidationError
from test_pipeline import validate

ROOT = Path(__file__).resolve().parents[2]
def sample(name):
    return json.loads((ROOT / f'contracts/examples/{name}.json').read_text())

def test_the_example_conforms():
    validate('source-receipt-capacity',sample('source-receipt-capacity'))

@pytest.mark.parametrize('field,value',[
    ('schemaVersion','2.0'),('storage','victim-store'),('dataMode','live'),('tenantId',''),('sourceInstanceId','bad source!'),
])
def test_the_envelope_stays_closed(field,value):
    body=sample('source-receipt-capacity');body[field]=value
    with pytest.raises(ValidationError):validate('source-receipt-capacity',body)

@pytest.mark.parametrize('field',['schemaVersion','storage','dataMode','tenantId','sourceInstanceId','items'])
def test_the_envelope_requires_every_published_field(field):
    body=sample('source-receipt-capacity');del body[field]
    with pytest.raises(ValidationError):validate('source-receipt-capacity',body)

def test_both_caps_are_reported_and_nothing_else_is():
    body=sample('source-receipt-capacity')
    assert [item['kind'] for item in body['items']]==['snapshot','binding-correction']
    body['items']=body['items'][:1]
    with pytest.raises(ValidationError):validate('source-receipt-capacity',body)

@pytest.mark.parametrize('field',['kind','kept','max','status','summary'])
def test_every_row_requires_every_published_field(field):
    body=sample('source-receipt-capacity');del body['items'][0][field]
    with pytest.raises(ValidationError):validate('source-receipt-capacity',body)

@pytest.mark.parametrize('value',['SNAPSHOT','corrections',''])
def test_the_reported_kind_is_a_closed_label(value):
    body=sample('source-receipt-capacity');body['items'][0]['kind']=value
    with pytest.raises(ValidationError):validate('source-receipt-capacity',body)

@pytest.mark.parametrize('value',['FULL','near-limit','',0])
def test_the_reported_status_is_a_closed_enum(value):
    body=sample('source-receipt-capacity');body['items'][0]['status']=value
    with pytest.raises(ValidationError):validate('source-receipt-capacity',body)

@pytest.mark.parametrize('value',[-1,1001,'940'])
def test_the_count_stays_inside_the_published_cap(value):
    body=sample('source-receipt-capacity');body['items'][0]['kept']=value
    with pytest.raises(ValidationError):validate('source-receipt-capacity',body)

def test_the_reported_cap_is_the_published_one():
    body=sample('source-receipt-capacity')
    for item in body['items']:
        assert item['max']==1000
        item['max']=999
        with pytest.raises(ValidationError):validate('source-receipt-capacity',body)
        item['max']=1000

@pytest.mark.parametrize('value',['line one\nline two','','  ','x'*201])
def test_a_summary_is_plain_single_line_text(value):
    body=sample('source-receipt-capacity');body['items'][0]['summary']=value
    with pytest.raises(ValidationError):validate('source-receipt-capacity',body)

def test_a_capacity_row_cannot_claim_a_cleanup_happened():
    body=sample('source-receipt-capacity')
    body['items'][0]['removed']=40
    with pytest.raises(ValidationError):validate('source-receipt-capacity',body)
    body=sample('source-receipt-capacity')
    body['items'][0]['raisedTo']=5000
    with pytest.raises(ValidationError):validate('source-receipt-capacity',body)

@pytest.mark.parametrize('status,kept',[('OK',0),('OK',799),('NEAR_LIMIT',800),('NEAR_LIMIT',999),('AT_LIMIT',1000)])
def test_every_published_status_boundary_is_a_valid_report(status,kept):
    body=sample('source-receipt-capacity')
    body['items'][0]['status']=status
    body['items'][0]['kept']=kept
    validate('source-receipt-capacity',body)

def test_the_envelope_never_echoes_client_authority():
    body=sample('source-receipt-capacity');body['permissions']='entity.manage'
    with pytest.raises(ValidationError):validate('source-receipt-capacity',body)
