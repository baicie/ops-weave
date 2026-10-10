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
def test_relation_execution_receipt_counts_written_and_pending_rows():
 x=execution();x.update(accepted=2,entityIds=[],relationIds=['10000000-0000-4000-8000-000000000003'],pending=[dict(index=1,code='FROM_UNRESOLVED')]);validate('workflow-runtime-execution',x)
 x.update(relationIds=[]);validate('workflow-runtime-execution',x)
 x.update(relationIds=['10000000-0000-4000-8000-000000000003'],pending=[dict(index=1,code='TO_UNRESOLVED')]);validate('workflow-runtime-execution',x)
@pytest.mark.parametrize('patch',[{'tenantId':'forged'},{'values':{'private':'body'}},{'dryRun':True},{'state':'RUNNING'},{'accepted':6},{'error':'STACK_TRACE'},{'state':'FAILED','error':None},{'settings':{'identityField':'password','nameField':'name'}},{'entityIds':['not-uuid']},{'relationIds':['not-uuid']},{'relationIds':['10000000-0000-4000-8000-000000000003','10000000-0000-4000-8000-000000000003']},{'pending':[{'index':0,'code':'GUESSED'}]},{'pending':[{'index':5,'code':'FROM_UNRESOLVED'}]},{'pending':[{'index':-1,'code':'FROM_UNRESOLVED'}]},{'pending':[{'index':0}]},{'pending':[{'index':0,'code':'TO_UNRESOLVED','note':'guessed'}]},{'pending':6},{'outcome':'written'}])
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
