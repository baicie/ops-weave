import copy
import json
from pathlib import Path
import pytest
from jsonschema import ValidationError
from test_workflow import validate
ROOT=Path(__file__).resolve().parents[2]
def example(kind):return json.loads((ROOT/f'contracts/examples/v2/workflow-log-output-{kind}.json').read_text(encoding='utf-8'))
@pytest.mark.parametrize('kind',['request','receipt','data','history','capability'])
def test_explicit_log_fixtures_validate(kind):validate('workflow-log-output-'+kind,example(kind))
@pytest.mark.parametrize('kind',['request','receipt','data','history','capability'])
@pytest.mark.parametrize('key',['tenantId','authority','endpoint','sql'])
def test_log_contracts_reject_request_identity_or_arbitrary_execution(kind,key):
    with pytest.raises(ValidationError):validate('workflow-log-output-'+kind,{**example(kind),key:'forged'})
@pytest.mark.parametrize('patch',[dict(state='RETRY'),dict(confirmed=1),dict(unknown=6),dict(unknown=1),dict(filtered=4),dict(error=None),dict(indices=[0,0]),dict(indices=[0]),dict(indices=[5]),dict(samples=[{'body':'private'}]),dict(batchDigest='bad')])
def test_closed_log_unknown_proof(patch):
    with pytest.raises(ValidationError):validate('workflow-log-output-receipt',{**example('receipt'),**patch})
@pytest.mark.parametrize('value',[[{'body':{}}],[{'body':['private']}],[{'x':9007199254740992}],[],[{'body':'x'*2049}],[{' ':True}], [{'x':None}]*6])
def test_log_input_scalar_quantity_and_text_budgets(value):
    x=example('request');x['samples']=value
    with pytest.raises(ValidationError):validate('workflow-log-output-request',x)
def test_log_text_is_business_data_and_is_absent_from_metadata():
    x=example('data');x['records'][0]['body']='  <script>untrusted()</script>\nline  ';validate('workflow-log-output-data',x)
    assert 'body' not in example('receipt') and 'samples' not in example('receipt')
    x['records'][0]['traceId']='bad'
    with pytest.raises(ValidationError):validate('workflow-log-output-data',x)
def test_log_history_is_bounded():
    x=example('history');x['items']*=21
    with pytest.raises(ValidationError):validate('workflow-log-output-history',x)
def test_complete_log_data_requires_the_full_expected_quantity():
    x=example('data');x['complete']=True
    with pytest.raises(ValidationError):validate('workflow-log-output-data',x)
