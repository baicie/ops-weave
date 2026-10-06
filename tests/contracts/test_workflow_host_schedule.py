import copy
import pytest
from jsonschema import ValidationError
from test_workflow import validate

def schedule():
    return dict(workflowId='fixture-host',revision=1,digest='sha256:'+'a'*64,settings=dict(identityField='entity_id',nameField='hostname'),intervalSeconds=60,generation=1,state='RUNNING',taskGeneration=1,activeScanId='10000000-0000-4000-8000-000000000118',accountedScanId=None,completedScans=0,confirmedRecords=0,sessionBatches=0,nextRunAt=None,lastSuccessAt=None,updatedAt='2026-10-04T01:00:00Z',error=None)

def test_schedule_and_empty_status_are_closed():
    validate('workflow-host-schedule',schedule())
    validate('workflow-host-schedule-status',dict(schemaVersion='2.0',available=True,mode='LOCAL_DEV_ENTITY',pollSeconds=5,maxSessionBatches=20,schedule=None,task=None))

@pytest.mark.parametrize('patch',[dict(tenantId='forged'),dict(authority={}),dict(intervalSeconds=59),dict(intervalSeconds=901),dict(sessionBatches=21),dict(completedScans=201),dict(confirmedRecords='1'),dict(state='FAILED',error=None),dict(error='upstream-private-text'),dict(nextRunAt='2026-10-04T01:01:00Z')])
def test_invalid_schedule_is_rejected(patch):
    s=schedule();s.update(patch)
    with pytest.raises(ValidationError):validate('workflow-host-schedule',s)

def test_receipt_separates_original_control_and_current_state():
    s=schedule();r=dict(requestId=s['activeScanId'],operation='START',commandDigest=s['digest'],acceptedAt=s['updatedAt'],schedule=s)
    validate('workflow-host-schedule-receipt',r)
    changed=copy.deepcopy(r);changed['operation']='STOP'
    with pytest.raises(ValidationError):validate('workflow-host-schedule-receipt',changed)
    changed['schedule']['state']='STOPPED';validate('workflow-host-schedule-receipt',changed)

def test_command_does_not_accept_authorization_or_source_pages():
    s=schedule();c=dict(requestId=s['activeScanId'],id=s['workflowId'],revision=1,digest=s['digest'],settings=s['settings'],intervalSeconds=60,expectedGeneration=0)
    validate('workflow-host-schedule-command',c)
    c['samples']=[]
    with pytest.raises(ValidationError):validate('workflow-host-schedule-command',c)

@pytest.mark.parametrize('field',['tenantId','authorization','name.invalid','x'*49])
def test_name_mapping_uses_existing_business_field_contract(field):
    s=schedule();s['settings']['nameField']=field
    with pytest.raises(ValidationError):validate('workflow-host-schedule',s)
