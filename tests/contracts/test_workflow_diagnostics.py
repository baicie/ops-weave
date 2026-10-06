import copy
import pytest
from jsonschema import ValidationError
from test_workflow import validate

def observation():
    counts=dict(coverage='PARTIAL',sampleRate=None,received=10,accepted=4,rejected=1,filtered=0,unknown=5,schemaMismatch=1,missingIdentity=0,invalidTimestamp=0,unitMismatch=None,nodes=[dict(nodeId='source_node',type='SOURCE',received=5,accepted=5,rejected=0,filtered=0,skipped=0,issues={})])
    return dict(id='10000000-0000-4000-8000-000000000126',reference=dict(id='diagnostic-fixture',revision=1,digest='sha256:'+'a'*64),kind='LOG_STREAM',generation=1,state='REJECTED',**{'from':'2026-10-05T00:00:00Z','till':'2026-10-05T00:01:00Z'},startedAt='2026-10-05T00:01:10Z',completedAt='2026-10-05T00:01:11Z',queueWaitMillis=None,relatedBatchId=None,error='INVALID_SAMPLE',result=counts)

def test_partial_observation_and_source_failure_are_truthful():
    o=observation();validate('workflow-diagnostic-observation',o)
    o.update(state='SOURCE_FAILED',error='SOURCE_UNAVAILABLE');o['result'].update(coverage='UNAVAILABLE',nodes=[])
    for key in ['received','accepted','rejected','filtered','unknown','schemaMismatch','missingIdentity','invalidTimestamp','unitMismatch']:o['result'][key]=None
    validate('workflow-diagnostic-observation',o)
    o['result']['received']=0
    with pytest.raises(ValidationError):validate('workflow-diagnostic-observation',o)

@pytest.mark.parametrize('patch',[{'body':'private'},{'entityIds':[]},{'generation':'1'},{'queueWaitMillis':0},{'error':'private exception'},{'state':'CHECKED'},{'state':'SOURCE_FAILED'},{'kind':'HOST_SCAN'},{'from':None}])
def test_observation_rejects_forged_scope_and_invented_measurements(patch):
    o=observation();o.update(patch)
    with pytest.raises(ValidationError):validate('workflow-diagnostic-observation',o)

@pytest.mark.parametrize('patch',[{'received':None},{'received':1001},{'accepted':'4'},{'sampleRate':1},{'unknown':0},{'unitMismatch':0},{'coverage':'COMPLETE'},{'nodes':[{}]}])
def test_partial_result_boundary(patch):
    o=observation();o['result'].update(patch)
    with pytest.raises(ValidationError):validate('workflow-diagnostic-observation',o)

def test_report_and_node_are_closed_and_bounded():
    o=observation();r=dict(schemaVersion='2.0',asOf=o['completedAt'],reference=o['reference'],observations=[o],truncated=False);validate('workflow-diagnostic-report',r)
    for patch in [{'truncated':True},{'observations':[o]*21},{'tenantId':'forged'}]:
        b=copy.deepcopy(r);b.update(patch)
        with pytest.raises(ValidationError):validate('workflow-diagnostic-report',b)
    n=o['result']['nodes'][0]
    for patch in [{'issues':{'RAW_BODY':1}},{'issues':{'TYPE_MISMATCH':'1'}},{'skipped':1001},{'value':'private'}]:
        b=copy.deepcopy(n);b.update(patch)
        with pytest.raises(ValidationError):validate('workflow-diagnostic-node',b)
