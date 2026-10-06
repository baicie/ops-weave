import copy,json
from pathlib import Path
import pytest
from jsonschema import ValidationError
from test_workflow import validate

def stored():return json.loads((Path(__file__).resolve().parents[2]/'contracts/examples/v2/workflow-diagnostic-stored.json').read_text(encoding='utf8'))
def test_private_scope_witness_and_public_projection_are_separate():
    s=stored();validate('workflow-diagnostic-stored',s);validate('workflow-diagnostic-observation',s['observation'])
    with pytest.raises(ValidationError):validate('workflow-diagnostic-observation',s)

@pytest.mark.parametrize('patch',[{'entityIds':['not-a-uuid']},{'entityIds':['30000000-0000-4000-8000-000000000127']*2},{'entityIds':[f'30000000-0000-4000-8000-{i:012d}' for i in range(6)]},{'records':[]},{'tenantId':'forged'}])
def test_private_record_rejects_unbounded_or_raw_scope_metadata(patch):
    s=stored();s.update(patch)
    with pytest.raises(ValidationError):validate('workflow-diagnostic-stored',s)

@pytest.mark.parametrize('patch',[{'from':'2026-10-05T00:00:00Z'},{'till':'2026-10-05T00:01:00Z'},{'result':{'received':6}},{'result':{'unitMismatch':0}},{'queueWaitMillis':0},{'result':{'unknown':1}},{'state':'REJECTED'}])
def test_host_page_counter_and_window_are_bounded(patch):
    s=stored()['observation']
    for k,v in patch.items():
        if k=='result':s[k].update(v)
        else:s[k]=v
    with pytest.raises(ValidationError):validate('workflow-diagnostic-observation',s)
