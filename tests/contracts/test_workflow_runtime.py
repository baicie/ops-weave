import json
from pathlib import Path
import pytest
from jsonschema import ValidationError
from test_workflow import validate
ROOT=Path(__file__).resolve().parents[2]
def execution():return json.loads((ROOT/'contracts/examples/v2/workflow-runtime-execution.json').read_text(encoding='utf-8'))
def test_runtime_execution_and_closed_status():
 x=execution();validate('workflow-runtime-execution',x)
 t={k:x[k] for k in ['workflowId','revision','digest','settings','error']};t.update(generation=1,state='RUNNING',cursor=x['createdAt'],cursorId=x['id'],updatedAt=x['createdAt'])
 validate('workflow-runtime',{'schemaVersion':'2.0','mode':'LOCAL_DEV_ENTITY','pollSeconds':5,'maxBatchRecords':5,'tasks':[t],'executions':[x]})
 t['state']='FAILED';t['error']='INVALID_SAMPLE';validate('workflow-runtime-task',t)
@pytest.mark.parametrize('patch',[{'tenantId':'forged'},{'values':{'private':'body'}},{'dryRun':True},{'state':'RUNNING'},{'accepted':6},{'error':'STACK_TRACE'},{'state':'FAILED','error':None},{'settings':{'identityField':'password','nameField':'name'}},{'entityIds':['not-uuid']}])
def test_runtime_receipt_rejects_untrusted_or_unbounded_metadata(patch):
 x=execution();x.update(patch)
 with pytest.raises(ValidationError):validate('workflow-runtime-execution',x)
def test_runtime_commands_pin_versions_and_use_closed_inputs():
 x=execution();body={k:x[k] for k in ['revision','digest','settings']};body.update(id=x['workflowId'],previewId=x['id'],samples=[{'source_id':'explicit','name':'LOCALTEST'}]);validate('workflow-runtime-execute-request',body)
 body['syncRunId']=x['id']
 with pytest.raises(ValidationError):validate('workflow-runtime-execute-request',body)
 body.pop('syncRunId');body['samples']*=6
 with pytest.raises(ValidationError):validate('workflow-runtime-execute-request',body)
 body={k:x[k] for k in ['revision','digest','settings']};body.update(requestId=x['id'],id=x['workflowId'],expectedGeneration=0);validate('workflow-runtime-control-request',body);body['tenantId']='forged'
 with pytest.raises(ValidationError):validate('workflow-runtime-control-request',body)
