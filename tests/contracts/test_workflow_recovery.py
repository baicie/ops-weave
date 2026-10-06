import json,copy
from pathlib import Path
import pytest
from jsonschema import ValidationError
from test_workflow import validate

def example(name):return json.loads((Path(__file__).resolve().parents[2]/('contracts/examples/v2/'+name+'.json')).read_text(encoding='utf8'))
@pytest.mark.parametrize('patch',[{'acknowledgeUncertainOutput':False},{'acknowledgeUncertainOutput':'true'},{'tenantId':'forged'},{'authority':{}},{'batchId':'1-1-1-1-1'},{'expectedGeneration':0},{'kind':'CODE'},{'revision':10001}])
def test_recovery_command_is_explicit_bounded_and_has_no_client_identity(patch):
    v=example('workflow-recovery-command');v.update(patch)
    with pytest.raises(ValidationError):validate('workflow-recovery-command',v)
@pytest.mark.parametrize('patch',[{'state':'CONFIRMED'},{'authority':{}},{'records':[]},{'confirmedRecords':200001},{'generation':1},{'preservedCursor':None},{'kind':'HTTP'},{'batchId':'1-1-1-1-1'}])
def test_closure_receipt_never_becomes_output_confirmation(patch):
    v=example('workflow-recovery-receipt');v.update(patch)
    with pytest.raises(ValidationError):validate('workflow-recovery-receipt',v)
def test_host_closure_uses_original_page_cursor_without_synthetic_time_window():
    v=example('workflow-recovery-receipt');v.update(kind='HOST_SCAN',preservedCursor=None);validate('workflow-recovery-receipt',v)
    v['preservedCursor']='2026-10-05T00:00:00Z'
    with pytest.raises(ValidationError):validate('workflow-recovery-receipt',v)

@pytest.mark.parametrize('kind',['metric','log'])
def test_version_scoped_status_allows_separate_generation_without_mixing_old_task(kind):
    v=example('workflow-'+kind+'-stream-status');v.update(task=None,batches=[],control={'generation':2,'startAllowed':True,'recoveryClosed':False})
    validate('workflow-'+kind+'-stream-status',v)

@pytest.mark.parametrize('kind',['metric','log'])
@pytest.mark.parametrize('control',[{'generation':-1,'startAllowed':True,'recoveryClosed':False},{'generation':2,'startAllowed':True,'recoveryClosed':True},{'generation':999999,'startAllowed':True,'recoveryClosed':False},{'generation':2,'startAllowed':True,'recoveryClosed':False,'tenantId':'forged'},{'generation':2,'startAllowed':'true','recoveryClosed':False}])
def test_version_control_metadata_remains_closed_and_bounded(kind,control):
    v=example('workflow-'+kind+'-stream-status');v['control']=control
    with pytest.raises(ValidationError):validate('workflow-'+kind+'-stream-status',v)

def test_host_receipt_never_discloses_private_scan_list_cursor():
    v=example('workflow-recovery-receipt');v.update(kind='HOST_SCAN',preservedCursor='ids-v1|'+'a'*64+'|7|5')
    with pytest.raises(ValidationError):validate('workflow-recovery-receipt',v)
