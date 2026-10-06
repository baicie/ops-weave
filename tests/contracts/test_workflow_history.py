from pathlib import Path
import copy,json
import pytest
from jsonschema import ValidationError
from test_workflow import validate
ROOT=Path(__file__).resolve().parents[2]/'contracts/examples/v2'
def example(name):return json.loads((ROOT/('workflow-history-'+name+'.json')).read_text(encoding='utf8'))
@pytest.mark.parametrize('name',['page','final-page','cursor'])
def test_closed_history_examples(name):validate('workflow-history-cursor' if name=='cursor' else 'workflow-history-page',example(name))
@pytest.mark.parametrize('patch',[{'tenantId':'forged'},{'owner':'forged'},{'watermark':42},{'coverage':'ALL_RUNS'},{'offset':1},{'offset':200},{'recordedCount':201},{'recordedCount':False},{'items':[]},{'items':None},{'nextCursor':None},{'nextCursor':'h1.abc=='},{'nextCursor':'h1.'+'a'*1024},{'hasMore':0},{'snapshotAt':'2026-10-05T00:01:11+00:00'},{'reference':{'id':'wrong'}}])
def test_pages_reject_private_scope_and_false_completion(patch):
 v=example('page');v.update(patch)
 with pytest.raises(ValidationError):validate('workflow-history-page',v)
def test_empty_recorded_scope_has_no_cursor_or_false_runtime_claim():
 v=example('page');v.update(recordedCount=0,items=[],nextCursor=None,hasMore=False);validate('workflow-history-page',v)
 v['items']=[example('page')['items'][0]]
 with pytest.raises(ValidationError):validate('workflow-history-page',v)
@pytest.mark.parametrize('patch',[{'schemaVersion':'1.0'},{'watermark':0},{'watermark':9223372036854775808},{'watermark':True},{'seen':0},{'seen':21},{'seen':200},{'recordedCount':20},{'lastId':'1-1-1-1-1'},{'tenant':'forged'},{'snapshotAt':'not-a-time'}])
def test_private_cursor_is_closed_and_bounded(patch):
 v=example('cursor');v.update(patch)
 with pytest.raises(ValidationError):validate('workflow-history-cursor',v)
def test_old_missing_measurements_are_valid_and_never_raw_values():
 v=example('final-page');o=v['items'][0];o.pop('dispatch',None);o.pop('sourceRead',None);o['queueWaitMillis']=None;validate('workflow-history-page',v)
 o['values']={'customer':'not-allowed'}
 with pytest.raises(ValidationError):validate('workflow-history-page',v)
def test_duplicate_items_and_false_final_cursor_fail():
 v=example('page');v['items'][1]=copy.deepcopy(v['items'][0])
 with pytest.raises(ValidationError):validate('workflow-history-page',v)
 v=example('final-page');v['nextCursor']='h1.U3ludGhldGljRml4dHVyZQ'
 with pytest.raises(ValidationError):validate('workflow-history-page',v)
