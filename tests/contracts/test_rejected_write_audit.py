import json
from pathlib import Path
import pytest
from jsonschema import ValidationError
from test_pipeline import validate

ROOT = Path(__file__).resolve().parents[2]
def sample(name):
    return json.loads((ROOT / f'contracts/examples/{name}.json').read_text())

def test_the_example_conforms():
    validate('rejected-write-audit',sample('rejected-write-audit'))

@pytest.mark.parametrize('field,value',[
    ('schemaVersion','2.0'),('storage','victim-store'),('dataMode','live'),('limit',0),('limit',101),('limit','20'),
    ('tenantId',''),('sourceInstanceId','bad source!'),
])
def test_the_envelope_stays_closed(field,value):
    body=sample('rejected-write-audit');body[field]=value
    with pytest.raises(ValidationError):validate('rejected-write-audit',body)

@pytest.mark.parametrize('field',['schemaVersion','storage','dataMode','tenantId','sourceInstanceId','limit','items'])
def test_the_envelope_requires_every_published_field(field):
    body=sample('rejected-write-audit');del body[field]
    with pytest.raises(ValidationError):validate('rejected-write-audit',body)

@pytest.mark.parametrize('field',['attemptId','kind','method','reasonCode','reasonSummary','actor','fieldNames','attemptedAt'])
def test_every_audit_row_requires_every_published_field(field):
    body=sample('rejected-write-audit');del body['items'][0][field]
    with pytest.raises(ValidationError):validate('rejected-write-audit',body)

@pytest.mark.parametrize('field',['permissions','token','fieldValues','vendorMessage'])
def test_the_envelope_never_echoes_client_authority(field):
    body=sample('rejected-write-audit');body[field]='override'
    with pytest.raises(ValidationError):validate('rejected-write-audit',body)

def test_the_page_caps_items_at_the_documented_bound():
    body=sample('rejected-write-audit');body['items']=body['items']*51
    with pytest.raises(ValidationError):validate('rejected-write-audit',body)

@pytest.mark.parametrize('value',['FIELD_REVIEW','BINDING_CORRECTION'])
def test_the_recorded_kind_is_a_closed_enum(value):
    body=sample('rejected-write-audit');body['items'][0]['kind']=value
    validate('rejected-write-audit',body)

@pytest.mark.parametrize('value',['review','STAGE_REVIEW','correct binding',''])
def test_the_refused_operation_is_one_of_the_allow_listed_labels(value):
    body=sample('rejected-write-audit');body['items'][0]['method']=value
    with pytest.raises(ValidationError):validate('rejected-write-audit',body)

@pytest.mark.parametrize('value',['SOURCE_REVIEW_CONFLICT','binding_changed','VENDOR_TIMEOUT',''])
def test_the_reason_is_one_of_the_stable_codes(value):
    body=sample('rejected-write-audit');body['items'][0]['reasonCode']=value
    with pytest.raises(ValidationError):validate('rejected-write-audit',body)

def test_every_stable_code_carries_its_fixed_summary():
    body=sample('rejected-write-audit')
    edit=dict(body['items'][0]);edit['reasonCode']='BINDING_UNCHANGED'
    edit['reasonSummary']='The correction would not change the binding.'
    body['items']=[edit]
    validate('rejected-write-audit',body)

@pytest.mark.parametrize('value',['line one\nline two','','  ','x'*201,'reason\u007f'])
def test_a_reason_summary_is_plain_single_line_text(value):
    body=sample('rejected-write-audit');body['items'][0]['reasonSummary']=value
    with pytest.raises(ValidationError):validate('rejected-write-audit',body)

@pytest.mark.parametrize('value',['','  padded  ','actor\n','x'*129])
def test_an_actor_is_recorded_without_control_characters_or_padding(value):
    body=sample('rejected-write-audit');body['items'][0]['actor']=value
    with pytest.raises(ValidationError):validate('rejected-write-audit',body)

def test_an_unusable_actor_is_recorded_as_unknown():
    body=sample('rejected-write-audit');body['items'][0]['actor']='unknown'
    validate('rejected-write-audit',body)

@pytest.mark.parametrize('value',[['name','ip','owner','environment'],['owner'],[]])
def test_only_allow_listed_field_names_are_reported(value):
    body=sample('rejected-write-audit');body['items'][0]['fieldNames']=value
    validate('rejected-write-audit',body)

@pytest.mark.parametrize('value',[['payload'],['serial_number'],['Owner'],['name','name'],['<script>']])
def test_a_refused_write_never_reports_an_unknown_or_repeated_field(value):
    body=sample('rejected-write-audit');body['items'][0]['fieldNames']=value
    with pytest.raises(ValidationError):validate('rejected-write-audit',body)

def test_a_refused_write_cannot_carry_a_field_value():
    body=sample('rejected-write-audit')
    body['items'][0]['fieldValues']={'owner':'sre'}
    with pytest.raises(ValidationError):validate('rejected-write-audit',body)

def test_a_refused_write_cannot_carry_a_vendor_message():
    body=sample('rejected-write-audit')
    body['items'][0]['vendorMessage']='CMDB said: owner already set'
    with pytest.raises(ValidationError):validate('rejected-write-audit',body)

@pytest.mark.parametrize('value',['not-a-uuid','','2026-09-26T09:41:12Z'])
def test_an_attempt_id_is_a_uuid(value):
    body=sample('rejected-write-audit');body['items'][0]['attemptId']=value
    with pytest.raises(ValidationError):validate('rejected-write-audit',body)

@pytest.mark.parametrize('value',['26-09-2026','2026-09-26','','2026-09-26T09:41:12.482913'])
def test_an_attempt_time_is_an_absolute_timestamp(value):
    body=sample('rejected-write-audit');body['items'][0]['attemptedAt']=value
    with pytest.raises(ValidationError):validate('rejected-write-audit',body)

def test_the_audit_never_claims_a_stored_decision():
    body=sample('rejected-write-audit')
    body['items'][0]['receiptId']='11111111-1111-4111-8111-111111111111'
    with pytest.raises(ValidationError):validate('rejected-write-audit',body)
