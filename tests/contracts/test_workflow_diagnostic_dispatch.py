import json, copy
from pathlib import Path
import pytest
from jsonschema import ValidationError
from test_workflow import validate
ROOT=Path(__file__).resolve().parents[2]/'contracts/examples/v2'

def stored():return json.loads((ROOT/'workflow-diagnostic-dispatch-stored.json').read_text(encoding='utf8'))
def test_measured_dispatch_and_legacy_examples():
 v=stored();validate('workflow-diagnostic-stored',v);validate('workflow-diagnostic-dispatch',v['observation']['dispatch']);del v['observation']['dispatch'];v['observation']['queueWaitMillis']=None;validate('workflow-diagnostic-stored',v);v['observation']['dispatch']=None;validate('workflow-diagnostic-stored',v)

@pytest.mark.parametrize('patch',[{'id':'1-1-1-1-1'},{'id':None},{'id':'10000000-0000-4000-8000-00000000013A'},{'enqueuedAt':None},{'enqueuedAt':'invalid'},{'enqueuedAt':'2026-10-05T01:00:00+01:00'},{'startedAt':None},{'startedAt':'invalid'},{'startedAt':'2026-10-05T01:00:00+01:00'},{'authority':{}},{'tenantId':'forged'},{'raw':'private'},{'source':{}},{'received':0}])
def test_dispatch_is_closed(patch):
 v=stored();v['observation']['dispatch'].update(patch)
 with pytest.raises(ValidationError):validate('workflow-diagnostic-stored',v)

@pytest.mark.parametrize('wait',[None,-1,3600001,True,False,'0',1.5])
def test_dispatch_requires_measured_bounded_integer(wait):
 v=stored();v['observation']['queueWaitMillis']=wait
 with pytest.raises(ValidationError):validate('workflow-diagnostic-stored',v)

@pytest.mark.parametrize('dispatch',[None,{},[],{'startedAt':'2026-10-05T00:00:00Z'}])
def test_numeric_wait_requires_dispatch_evidence(dispatch):
 v=stored();v['observation']['dispatch']=dispatch
 with pytest.raises(ValidationError):validate('workflow-diagnostic-stored',v)

def test_zero_wait_is_measured_and_missing_evidence_stays_unknown():
 v=stored();validate('workflow-diagnostic-stored',v);del v['observation']['dispatch']
 with pytest.raises(ValidationError):validate('workflow-diagnostic-stored',v)
 v['observation']['queueWaitMillis']=None;validate('workflow-diagnostic-stored',v)
