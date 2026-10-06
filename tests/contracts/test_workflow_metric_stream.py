import copy
import json
from pathlib import Path
import pytest
from jsonschema import ValidationError
from test_workflow import validate
from test_workflow_control import receipt

UUID='10000000-0000-4000-8000-000000000117'
def task():
    t=receipt()['task']
    return {k:t[k] for k in ['workflowId','revision','digest','generation','state','cursor','updatedAt','error']}|dict(pendingBatchId=None,confirmedWindows=0,confirmedPoints=0,sessionBatches=0)
def status():return dict(schemaVersion='2.0',available=True,mode='LOCAL_DEV_METRIC',windowSeconds=60,settleSeconds=10,maxBatchPoints=600,maxSessionBatches=20,maxHistoryRequests=20,lookbackSeconds=60,task=task(),batches=[])
def test_status_is_closed_and_private_authority_never_wire():
    s=status();validate('workflow-metric-stream-status',s)
    private=copy.deepcopy(s['task']);private['authority']=None;validate('workflow-metric-stream-task-storage',private)
    with pytest.raises(ValidationError):validate('workflow-metric-stream-task',private)
@pytest.mark.parametrize('patch',[dict(tenantId='forged'),dict(sessionBatches=21),dict(state='FAILED',error=None),dict(error='vendor-private-text'),dict(confirmedWindows=201),dict(pendingBatchId='invalid'),dict(confirmedPoints='1')])
def test_task_rejects_false_metadata(patch):
    t=task();t.update(patch)
    with pytest.raises(ValidationError):validate('workflow-metric-stream-task',t)
def test_control_request_and_receipt_keep_operation_and_original_snapshot():
    t=task();r=dict(requestId=UUID,operation='START',commandDigest=t['digest'],acceptedAt=t['updatedAt'],task=t)
    validate('workflow-metric-stream-receipt',r)
    r['operation']='STOP'
    with pytest.raises(ValidationError):validate('workflow-metric-stream-receipt',r)
    r['task']['state']='STOPPED';validate('workflow-metric-stream-receipt',r)
    c=dict(requestId=UUID,id=t['workflowId'],revision=t['revision'],digest=t['digest'],expectedGeneration=0);validate('workflow-metric-stream-command',c)
    c['samples']=[]
    with pytest.raises(ValidationError):validate('workflow-metric-stream-command',c)
def test_schemas_and_migration_contain_no_point_value_storage():
    root=Path(__file__).resolve().parents[2]
    props=json.loads((root/'contracts/schemas/v2/workflow-metric-stream-batch.schema.json').read_text(encoding='utf-8'))['properties']
    assert not {'records','samples','values','raw','secret','token'}&props.keys()
    migration=(root/'db/migrations/platform/V044__workflow_metric_stream.sql').read_text(encoding='utf-8')
    assert 'workflow_metric_stream_batch' in migration and 'workflow_version' in migration

def batch():
    keys=['tenant_id','owner_scope','source_instance_id','external_item_id','host_external_id','metric_key','unit','mapping_id','mapping_revision','mapping_digest','workflow_id','workflow_revision','workflow_digest','configuration_digest','data_mode','collection_mode']
    labels={key:'fixture' for key in keys};labels.update(owner_scope='a'*64,data_mode='zabbix-jsonrpc',collection_mode='WINDOW_60S')
    return dict(id=UUID,workflowId='stream-fixture',revision=1,digest='sha256:'+'a'*64)|{
      'from':'2026-10-04T00:00:00Z','till':'2026-10-04T00:01:00Z','createdAt':'2026-10-04T00:01:10Z','updatedAt':'2026-10-04T00:01:10Z','state':'CONFIRMED','inputCount':1,'filtered':0,'collapsed':0,'labels':labels,'batchDigest':'sha256:'+'a'*64,'timestamps':[1791072000000],'error':None}

def test_bounded_extended_proof_keeps_legacy_shape_and_links_a_new_late_batch():
    b=batch();validate('workflow-metric-stream-batch',b)
    b.update(reconcilesBatchId=UUID,latePoints=1);validate('workflow-metric-stream-batch',b)
    b['latePoints']=0
    with pytest.raises(ValidationError):validate('workflow-metric-stream-batch',b)
    b.update(reconcilesBatchId=None,latePoints=0);validate('workflow-metric-stream-batch',b)
    del b['latePoints']
    with pytest.raises(ValidationError):validate('workflow-metric-stream-batch',b)

@pytest.mark.parametrize('patch',[dict(inputCount=601),dict(latePoints=601,reconcilesBatchId=UUID),dict(records=[]),dict(reconcilesBatchId='invalid',latePoints=1)])
def test_overbudget_and_untrusted_late_metadata_are_closed(patch):
    b=batch();b.update(patch)
    with pytest.raises(ValidationError):validate('workflow-metric-stream-batch',b)
