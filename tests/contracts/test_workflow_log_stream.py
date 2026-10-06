import copy
import json
from pathlib import Path
import pytest
from jsonschema import ValidationError
from test_workflow import validate
from test_workflow_metric_stream import task as metric_task

UUID='10000000-0000-4000-8000-000000000124'
def task():
    t=metric_task();t['confirmedRecords']=t.pop('confirmedPoints');return t
def batch():
    return dict(id=UUID,workflowId='log-stream-fixture',revision=1,digest='sha256:'+'a'*64,**{'from':'2026-10-04T00:00:00Z','till':'2026-10-04T00:01:00Z'},createdAt='2026-10-04T00:01:10Z',updatedAt='2026-10-04T00:01:10Z',state='CONFIRMED',inputCount=1,filtered=0,deduplicated=0,indices=[0],positions=['2026-10-04T00:00:00.000000001Z'],inputDigest='sha256:'+'b'*64,batchDigest='sha256:'+'c'*64,error=None,reconcilesBatchId=None)
def test_closed_public_and_private_task():
    t=task();validate('workflow-log-stream-task',t);stored=t|{'authority':None};validate('workflow-log-stream-task-storage',stored)
    with pytest.raises(ValidationError):validate('workflow-log-stream-task',stored)
    t.update(state='FAILED',error='SOURCE_WINDOW_CHANGED');validate('workflow-log-stream-task',t)
@pytest.mark.parametrize('patch',[{'body':'private'},{'tenantId':'forged'},{'positions':['bad']},{'inputCount':1001},{'indices':[1000]},{'deduplicated':1},{'state':'UNKNOWN','error':None},{'state':'IN_FLIGHT','indices':[]}])
def test_closed_batch_rejects_untrusted_metadata(patch):
    b=batch();b.update(patch)
    with pytest.raises(ValidationError):validate('workflow-log-stream-batch',b)
def test_dense_nanosecond_positions_and_empty_filtered_late_batch():
    b=batch();b.update(inputCount=1000,indices=list(range(1000)),positions=[f'2026-10-04T00:00:00.{i:09d}Z' for i in range(1000)]);validate('workflow-log-stream-batch',b)
    b.update(reconcilesBatchId='20000000-0000-4000-8000-000000000124',indices=[],filtered=1,deduplicated=999);validate('workflow-log-stream-batch',b)
def test_paged_records_complete_means_whole_batch_not_page():
    b=batch();r=dict(index=0,position=b['positions'][0],eventTime=b['positions'][0],body='Synthetic Fixture\n<script>',severityText=None,serviceName=None,traceId=None,spanId=None)
    d=dict(batchId=UUID,readAt=b['updatedAt'],storage='clickhouse',expectedRecords=1000,complete=True,afterIndex=-1,nextIndex=None,records=[r]);validate('workflow-log-stream-data',d)
    d['records']*=51
    with pytest.raises(ValidationError):validate('workflow-log-stream-data',d)
def test_commands_and_verification_never_take_source_values_or_identity():
    t=task();command=dict(requestId=UUID,id=t['workflowId'],revision=1,digest=t['digest'],expectedGeneration=0);validate('workflow-log-stream-command',command)
    command['samples']=[]
    with pytest.raises(ValidationError):validate('workflow-log-stream-command',command)
    validate('workflow-log-stream-verification',{'batchId':UUID})
    with pytest.raises(ValidationError):validate('workflow-log-stream-verification',{'batchId':UUID,'tenantId':'forged'})
def test_journal_schema_excludes_raw_values():
    root=Path(__file__).resolve().parents[2];props=json.loads((root/'contracts/schemas/v2/workflow-log-stream-batch.schema.json').read_text(encoding='utf-8'))['properties']
    assert not {'body','records','samples','raw','token','endpoint'}&props.keys()
    assert 'workflow_version' in (root/'db/migrations/platform/V048__workflow_log_stream.sql').read_text(encoding='utf-8')
