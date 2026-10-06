import copy
import pytest
from jsonschema import ValidationError
from test_workflow import validate
from test_workflow_authority import task

def receipt():
    t=task()
    return dict(requestId='10000000-0000-4000-8000-000000000114',operation='START',commandDigest=t['digest'],createdAt=t['updatedAt'],task=t)

def test_control_receipts_keep_public_and_private_task_formats_separate():
    x=receipt();validate('workflow-runtime-control-receipt',x)
    x['operation']='STOP';x['task']['state']='STOPPED';validate('workflow-runtime-control-receipt',x)
    s=copy.deepcopy(x);s['task']['authority']=s['task'].pop('authorization');s['task']['authority'].update(issuer='https://issuer.example.invalid',externalSubject='fixture',grantDigest=x['commandDigest'])
    validate('workflow-runtime-control-storage',s)
    with pytest.raises(ValidationError):validate('workflow-runtime-control-receipt',s)

@pytest.mark.parametrize('patch',[dict(requestId='bad'),dict(commandDigest='bad'),dict(tenantId='forged'),dict(operation='RETRY'),dict(createdAt='bad')])
def test_control_receipt_is_closed_and_fixed(patch):
    x=receipt();x.update(patch)
    with pytest.raises(ValidationError):validate('workflow-runtime-control-receipt',x)

def test_stop_receipt_cannot_report_a_new_running_task():
    x=receipt();x['operation']='STOP'
    with pytest.raises(ValidationError):validate('workflow-runtime-control-receipt',x)

def test_control_requests_require_original_uuid_and_bounded_generation():
    x=receipt();t=x['task'];body=dict(requestId=x['requestId'],id=t['workflowId'],revision=1,digest=t['digest'],settings=t['settings'],expectedGeneration=0)
    validate('workflow-runtime-control-request',body)
    body['expectedGeneration']=1000000;validate('workflow-runtime-control-request',body)
    for patch in [dict(requestId='bad'),dict(expectedGeneration=1000001),dict(operation='START')]:
        candidate={**body,**patch}
        with pytest.raises(ValidationError):validate('workflow-runtime-control-request',candidate)
    body.pop('requestId')
    with pytest.raises(ValidationError):validate('workflow-runtime-control-request',body)

def test_generation_reserve_allows_only_a_terminal_stop():
    x=receipt();x['operation']='STOP';x['task'].update(state='STOPPED',generation=1000001)
    validate('workflow-runtime-control-receipt',x)
    for schema in ['workflow-runtime-task','workflow-runtime-task-storage']:
        t=copy.deepcopy(x['task']);t.pop('authorization')
        validate(schema,t)
        for state,error in [('RUNNING',None),('FAILED','SOURCE_UNAVAILABLE')]:
            t.update(state=state,error=error)
            with pytest.raises(ValidationError):validate(schema,t)

def test_control_receipt_example_is_explicit_fixture():
    import json
    from test_workflow_runtime import ROOT
    x=json.loads((ROOT/'contracts/examples/v2/workflow-runtime-control-receipt.json').read_text(encoding='utf-8'))
    assert x['task']['workflowId'].startswith('fixture-')
    validate('workflow-runtime-control-receipt',x)
