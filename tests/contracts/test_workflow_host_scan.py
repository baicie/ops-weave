import copy
import pytest
from jsonschema import ValidationError
from test_workflow import validate
from test_workflow_control import receipt

UUID='10000000-0000-4000-8000-000000000116'
def checkpoint():
    t=receipt()['task']
    return {k:t[k] for k in ['workflowId','revision','digest','generation','updatedAt']}|dict(scanId=UUID,pendingBatchId=None,confirmedBatches=0,confirmedRecords=0,complete=False)
def batch():
    t=receipt()['task']
    return {k:t[k] for k in ['workflowId','revision','digest','settings','updatedAt']}|dict(id=UUID,scanId=UUID,sequence=1,complete=True,observedAt=t['updatedAt'],state='CONFIRMED',entityIds=[UUID],error=None,recordCount=1)
def test_public_scan_does_not_expose_recovery_bodies_or_source_cursors():
    c=checkpoint();b=batch();validate('workflow-host-scan',dict(schemaVersion='2.0',checkpoint=c,batches=[b]))
    s=copy.deepcopy(b);s.pop('recordCount');s.update(beforeCursor=None,nextCursor=None,records=[dict(name='Fixture host',ip='127.0.0.1',lifecycle='ACTIVE',entity_id=UUID)])
    validate('workflow-host-batch-storage',s)
    with pytest.raises(ValidationError):validate('workflow-host-batch',s)
    c['nextCursor']=None;validate('workflow-host-checkpoint-storage',c)
    with pytest.raises(ValidationError):validate('workflow-host-checkpoint',c)
@pytest.mark.parametrize('patch',[dict(recordCount=6),dict(error='EXCEPTION_TEXT'),dict(state='CONFIRMED',error='OUTPUT_UNAVAILABLE'),dict(state='UNKNOWN'),dict(tenantId='forged'),dict(entityIds=[UUID,UUID]),dict(id='bad')])
def test_batch_rejects_extra_identity_invalid_counts_and_false_states(patch):
    b=batch();b.update(patch)
    with pytest.raises(ValidationError):validate('workflow-host-batch',b)
def test_complete_scan_cannot_keep_an_unconfirmed_batch():
    c=checkpoint();c.update(complete=True,pendingBatchId=UUID)
    with pytest.raises(ValidationError):validate('workflow-host-checkpoint',c)
def test_resume_is_a_new_running_control_receipt():
    r=receipt();r['operation']='RESUME';validate('workflow-runtime-control-receipt',r)
    r['task']['state']='STOPPED'
    with pytest.raises(ValidationError):validate('workflow-runtime-control-receipt',r)
