import copy
import pytest
from jsonschema import ValidationError
from test_workflow_standard_metric import validate, example

def test_fixed_log_source_and_server_controlled_sample_examples():
    d=example('workflow-real-log-definition');validate('workflow-definition',d)
    validate('workflow-source',d['source']);validate('workflow-log-source-pin',d['source']['log'])
    validate('workflow-log-source-page',example('workflow-log-source-page'))
    validate('workflow-log-source-output-request',example('workflow-log-source-output-request'))
    validate('workflow-log-output-capability',example('workflow-log-source-output-capability'))

@pytest.mark.parametrize('patch',[{'itemId':'0'},{'hostId':'../host'},{'inspectionId':'not-uuid'},{'sourceKey':''},{'sourceKey':'x'*2049},{'sourceKey':'bad\nkey'},{'sourceUnit':'x'*65},{'sourceValueType':'TEXT'},{'digest':'bad'},{'body':'untrusted'},{'tenantId':'forged'},{'url':'http://other'}])
def test_log_pin_is_closed_metadata_only(patch):
    d=example('workflow-real-log-definition');d['source']['log'].update(patch)
    with pytest.raises(ValidationError):validate('workflow-definition',d)

@pytest.mark.parametrize('field',['configuration','log'])
def test_log_input_requires_fixed_connection_and_item(field):
    d=example('workflow-real-log-definition');del d['source'][field]
    with pytest.raises(ValidationError):validate('workflow-definition',d)

@pytest.mark.parametrize('kind',['MANUAL_SAMPLE','ZABBIX_HOST','ZABBIX_METRIC'])
def test_other_kinds_cannot_carry_log_pin(kind):
    d=example('workflow-real-log-definition');d['source']['kind']=kind
    with pytest.raises(ValidationError):validate('workflow-definition',d)

@pytest.mark.parametrize('target',[{'kind':'METRIC','schemaVersion':'1.0'},{'id':'builtin.host','revision':1,'digest':'sha256:'+'a'*64}])
def test_log_source_requires_log_output(target):
    d=example('workflow-real-log-definition');d['target']=target
    with pytest.raises(ValidationError):validate('workflow-definition',d)

@pytest.mark.parametrize('field',['samples','previewId','tenantId','userId','source','log','url'])
def test_source_write_cannot_take_client_records_or_identity(field):
    command=example('workflow-log-source-output-request');command[field]='forged'
    with pytest.raises(ValidationError):validate('workflow-log-source-output-request',command)

def test_log_selection_preserves_unmapped_metadata_and_rejects_numeric_items():
    page=example('workflow-log-source-page');assert page['items'][0]['item']['mapping'] is None
    page['items'][0]['item']['sourceValueType']='FLOAT'
    with pytest.raises(ValidationError):validate('workflow-log-source-page',page)
