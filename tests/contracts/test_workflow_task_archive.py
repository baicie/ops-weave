import copy,json
from pathlib import Path
import pytest
from jsonschema import ValidationError
from test_workflow import validate

def example():return json.loads((Path(__file__).resolve().parents[2]/'contracts/examples/v2/workflow-task-archive.json').read_text(encoding='utf8'))
@pytest.mark.parametrize('patch',[{'authority':{}},{'cursor':'private'},{'records':[]},{'tenantId':'forged'},{'owner':'forged'},{'nextGeneration':1000000},{'schemaVersion':'1.0'},{'kind':'SHELL'}])
def test_archive_is_terminal_bounded_metadata_only(patch):
 v=example();v.update(patch)
 with pytest.raises(ValidationError):validate('workflow-task-archive',v)
@pytest.mark.parametrize('patch',[{'state':'RUNNING'},{'generation':999999},{'authorization':{}},{'pendingBatchId':'1-1-1-1-1'},{'state':'FAILED','error':None}])
def test_terminal_snapshot_is_closed_and_uses_existing_task_semantics(patch):
 v=example();v['task'].update(patch)
 with pytest.raises(ValidationError):validate('workflow-task-archive',v)
def test_stopped_and_failed_archives_use_fixed_version_and_no_payload():
 v=example();validate('workflow-task-archive',v);v['task'].update(state='FAILED',error='SOURCE_CHANGED');validate('workflow-task-archive',v)

@pytest.mark.parametrize('value',[True,'true',None,1])
def test_resume_availability_never_grants_execution_to_missing_task(value):
 v=json.loads((Path(__file__).resolve().parents[2]/'contracts/examples/v2/workflow-log-stream-status.json').read_text(encoding='utf8'));v['task']=None;v['resumeAllowed']=value
 with pytest.raises(ValidationError):validate('workflow-log-stream-status',v)

@pytest.mark.parametrize('value',[None,'private',True,['not-a-uuid'],['10000000-0000-4000-8000-000000000130']*2,['10000000-0000-4000-8000-'+str(n).zfill(12) for n in range(21)]])
def test_log_uncertainty_observation_is_bounded_closed_metadata(value):
 v=json.loads((Path(__file__).resolve().parents[2]/'contracts/examples/v2/workflow-log-stream-status.json').read_text(encoding='utf8'));v['uncertainBatchIds']=value
 with pytest.raises(ValidationError):validate('workflow-log-stream-status',v)

def test_log_uncertainty_ids_do_not_rewrite_original_batch():
 v=json.loads((Path(__file__).resolve().parents[2]/'contracts/examples/v2/workflow-log-stream-status.json').read_text(encoding='utf8'));v['uncertainBatchIds']=[];validate('workflow-log-stream-status',v)
