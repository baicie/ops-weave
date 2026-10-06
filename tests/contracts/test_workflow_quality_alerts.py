from pathlib import Path
import json,copy
import pytest
from jsonschema import ValidationError
from test_workflow import validate
ROOT=Path(__file__).resolve().parents[2]/'contracts/examples/v2'
OPENAPI=Path(__file__).resolve().parents[2]/'contracts/openapi/platform-draft.yaml'
def example(name):return json.loads((ROOT/('workflow-quality-alert-'+name+'.json')).read_text(encoding='utf8'))
@pytest.mark.parametrize('name',['command','configuration','receipt','status'])
def test_closed_examples(name):validate('workflow-quality-alert-'+name,example(name))
@pytest.mark.parametrize('patch',[{'tenantId':'forged'},{'rules':None},{'rules':[{'kind':'LEASE_CONFLICT','threshold':1}]},{'rules':[{'kind':'TASK_FAILURE','threshold':2}]},{'rules':[{'kind':'QUEUE_WAIT','threshold':0}]},{'rules':[{'kind':'QUEUE_WAIT','threshold':3600001}]},{'rules':[{'kind':'SOURCE_FAILURES','threshold':21}]},{'rules':[{'kind':'SOURCE_FAILURES','threshold':1.5}]},{'rules':[{'kind':'SOURCE_FAILURES','threshold':True}]},{'rules':[{'kind':'SOURCE_FAILURES','threshold':1,'notify':'any'}]},{'rules':[{'kind':'SOURCE_FAILURES','threshold':1},{'kind':'SOURCE_FAILURES','threshold':2}]},{'expectedVersion':-1},{'expectedVersion':1000000},{'expectedVersion':False},{'windowSeconds':59},{'windowSeconds':86401},{'windowSeconds':'600'},{'requestId':'1-1-1-1-1'}])
def test_commands_reject_forgery_and_unbounded_values(patch):
 v=example('command');v.update(patch)
 with pytest.raises(ValidationError):validate('workflow-quality-alert-command',v)
@pytest.mark.parametrize('patch',[{'reference':{'id':'forged'}},{'editVersion':0},{'editVersion':1000001},{'updatedAt':'2026-10-05T13:00:00+01:00'},{'rules':[{'kind':'QUEUE_WAIT','threshold':1},{'kind':'QUEUE_WAIT','threshold':2}]},{'owner':'forged'}])
def test_configuration_is_bounded(patch):
 v=example('configuration');v.update(patch)
 with pytest.raises(ValidationError):validate('workflow-quality-alert-configuration',v)
@pytest.mark.parametrize('patch',[{'state':'HEALTHY'},{'value':0},{'reason':None},{'evidenceIds':['1-1-1-1-1']},{'evidenceIds':['10000000-0000-4000-8000-000000000134']*2},{'sampleCount':21},{'missingCount':-1},{'truncated':0},{'body':'private'}])
def test_unknown_evaluation_keeps_missingness(patch):
 v=example('status')['evaluations'][0];v.update(patch)
 with pytest.raises(ValidationError):validate('workflow-quality-alert-evaluation',v)
def test_unconfigured_and_disabled_are_distinct():
 v=example('status');v.update(configuration=None,**{'from':None,'till':None},evaluations=[]);validate('workflow-quality-alert-status',v);v['from']='2026-10-05T11:50:00Z'
 with pytest.raises(ValidationError):validate('workflow-quality-alert-status',v)
 v=example('status');v['configuration']['rules']=[];v['evaluations']=[];validate('workflow-quality-alert-status',v)
def test_triggered_queue_can_preserve_missing_other_measurements():
 v=example('status')['evaluations'][2];v.update(state='TRIGGERED',value=275,sampleCount=1,missingCount=1,evidenceIds=['10000000-0000-4000-8000-000000000134'],reason=None);validate('workflow-quality-alert-evaluation',v)

def test_quality_alert_routes_are_registered_in_platform_contract():
    text=OPENAPI.read_text(encoding='utf-8')
    root='/api/v1/integrations/workflows/quality/workflows/{id}/versions/{revision}/alerts'
    assert root in text
    assert root+'/configure' in text
    assert root+'/commands/{requestId}' in text
    assert 'operationId: getWorkflowQualityAlertStatus' in text
    assert 'operationId: configureWorkflowQualityAlerts' in text
    assert 'operationId: getWorkflowQualityAlertReceipt' in text
    assert '../schemas/v2/workflow-quality-alert-status.schema.json' in text
    assert '../schemas/v2/workflow-quality-alert-command.schema.json' in text
    assert '../schemas/v2/workflow-quality-alert-receipt.schema.json' in text
