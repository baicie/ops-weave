import copy
import pytest
from jsonschema import ValidationError
from test_workflow import validate
from test_workflow_runtime import execution

def authorization():
    return dict(id='10000000-0000-4000-8000-000000000113',issuedAt='2026-10-04T00:00:00Z',expiresAt='2026-10-04T00:15:00Z',maxBatches=20,consumedBatches=1)

def task():
    x=execution()
    return dict(workflowId=x['workflowId'],revision=x['revision'],digest=x['digest'],settings=x['settings'],generation=1,state='RUNNING',cursor=x['createdAt'],cursorId=x['id'],updatedAt='2026-10-04T00:00:01Z',error=None,authorization=authorization())

def test_public_authorization_and_private_storage_are_separate():
    t=task();validate('workflow-runtime-task',t)
    validate('workflow-runtime',dict(schemaVersion='2.0',mode='DELEGATED_ENTITY',backgroundAvailable=True,pollSeconds=5,maxBatchRecords=5,tasks=[t],executions=[]))
    s=copy.deepcopy(t);s['authority']=s.pop('authorization');s['authority'].update(issuer='https://issuer.example.invalid',externalSubject='fixture-operator',grantDigest=t['digest'])
    validate('workflow-runtime-task-storage',s)
    with pytest.raises(ValidationError):validate('workflow-runtime-task',s)
    with pytest.raises(ValidationError):validate('workflow-runtime-task-storage',t)
    validate('workflow-background-policy',dict(schemaVersion='1.0',subjects=['fixture-operator']))

@pytest.mark.parametrize('patch',[dict(tenantId='forged'),dict(issuer='https://issuer.example.invalid'),dict(permissions=['entity.manage']),dict(maxBatches=21),dict(consumedBatches=-1),dict(id='invalid'),dict(credential='not-an-authority')])
def test_public_authority_rejects_private_or_unbounded_fields(patch):
    a=authorization();a.update(patch)
    with pytest.raises(ValidationError):validate('workflow-runtime-authorization',a)

@pytest.mark.parametrize('subjects',[['same','same'],['x']*21,[''],['control\x01']])
def test_policy_is_bounded_and_closed(subjects):
    with pytest.raises(ValidationError):validate('workflow-background-policy',dict(schemaVersion='1.0',subjects=subjects))

@pytest.mark.parametrize('error',['AUTHORIZATION_EXPIRED','AUTHORIZATION_REVOKED','EXECUTION_LIMIT'])
def test_stable_failure_preserves_public_authority_and_execution_reference(error):
    t=task();t.update(state='FAILED',error=error);validate('workflow-runtime-task',t)
    x=execution();x.update(state='FAILED',error=error,authorizationId=t['authorization']['id']);validate('workflow-runtime-execution',x)

def test_start_cannot_supply_its_own_authority_or_permissions():
    x=execution();body={k:x[k] for k in ['revision','digest','settings']};body.update(requestId=x['id'],id=x['workflowId'],expectedGeneration=0)
    for key,value in [('authorization',authorization()),('authority',authorization()),('tenantId','forged'),('permissions',['entity.manage'])]:
        bad=copy.deepcopy(body);bad[key]=value
        with pytest.raises(ValidationError):validate('workflow-runtime-control-request',bad)

def test_delegated_status_requires_explicit_background_availability():
    x=dict(schemaVersion='2.0',mode='DELEGATED_ENTITY',pollSeconds=5,maxBatchRecords=5,tasks=[],executions=[])
    with pytest.raises(ValidationError):validate('workflow-runtime',x)
    x['backgroundAvailable']=False;validate('workflow-runtime',x)
