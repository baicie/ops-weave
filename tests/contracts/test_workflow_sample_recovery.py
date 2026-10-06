import json,copy
from pathlib import Path
import pytest
from jsonschema import ValidationError
from test_workflow import validate

def sample(kind):return json.loads((Path(__file__).resolve().parents[2]/f'contracts/examples/v2/workflow-sample-recovery-{kind}.json').read_text(encoding='utf8'))
@pytest.mark.parametrize('kind',['command','receipt','status'])
def test_sample_recovery_examples(kind):validate('workflow-sample-recovery-'+kind,sample(kind))
@pytest.mark.parametrize('patch',[{'authority':{}},{'tenantId':'forged'},{'samples':[]},{'generation':1},{'kind':'LOG_STREAM'},{'acknowledgeUncertainOutput':False},{'acknowledgeUncertainOutput':'true'},{'batchId':'1-1-1-1-1'},{'batchDigest':'private'},{'expectedUpdatedAt':'2026-10-05T00:00:00+00:00'}])
def test_closure_command_is_metadata_bound_to_original_sample(patch):
 v=sample('command');v.update(patch)
 with pytest.raises(ValidationError):validate('workflow-sample-recovery-command',v)
@pytest.mark.parametrize('patch',[{'records':[]},{'points':[]},{'cursor':'private'},{'state':'CONFIRMED'},{'uncertainRecords':0},{'uncertainRecords':6},{'uncertainRecords':True},{'kind':'SHELL'},{'schemaVersion':'1.0'},{'acceptedAt':None}])
def test_closure_never_becomes_confirmation_or_payload(patch):
 v=sample('receipt');v.update(patch)
 with pytest.raises(ValidationError):validate('workflow-sample-recovery-receipt',v)
@pytest.mark.parametrize('kind',['METRIC_SAMPLE','LOG_SAMPLE'])
def test_sample_kinds_and_missing_closure_remain_distinct(kind):
 v=sample('receipt');v['kind']=kind;validate('workflow-sample-recovery-receipt',v);validate('workflow-sample-recovery-status',{'schemaVersion':'2.0','closure':None})
