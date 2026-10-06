import json,copy
from pathlib import Path
import pytest
from jsonschema import ValidationError
from test_workflow import validate
ROOT=Path(__file__).resolve().parents[2]/'contracts/examples/v2'
def source():return json.loads((ROOT/'workflow-diagnostic-source.json').read_text(encoding='utf8'))
def stored():return json.loads((ROOT/'workflow-diagnostic-source-stored.json').read_text(encoding='utf8'))
def test_measured_source_and_legacy_storage_examples():
 validate('workflow-diagnostic-source',source());validate('workflow-diagnostic-stored',stored());v=stored();del v['observation']['sourceRead'];validate('workflow-diagnostic-stored',v);v['observation']['sourceRead']=None;validate('workflow-diagnostic-stored',v)
@pytest.mark.parametrize('patch',[{'attempts':0},{'attempts':2},{'completed':True},{'completed':0},{'failed':1},{'received':None},{'received':1001},{'failureCode':'UNIT_CHANGED'},{'body':'private'},{'endpoint':'private'},{'authority':{}},{'completedAt':None}])
def test_source_success_is_closed_and_measured(patch):
 v=source();v.update(patch)
 with pytest.raises(ValidationError):validate('workflow-diagnostic-source',v)
@pytest.mark.parametrize('reason',['UNAVAILABLE','ACCESS_DENIED','INCOMPLETE_WINDOW','INVALID_RESPONSE','METADATA_CHANGED','SOURCE_KEY_CHANGED','VALUE_TYPE_CHANGED','UNIT_CHANGED','OTHER_FAILURE'])
def test_failure_preserves_missing_record_denominator(reason):
 v=source();v.update(completed=0,failed=1,received=None,failureCode=reason);validate('workflow-diagnostic-source',v);v['received']=0
 with pytest.raises(ValidationError):validate('workflow-diagnostic-source',v)
@pytest.mark.parametrize('reason,error',[('UNAVAILABLE','SOURCE_UNAVAILABLE'),('INCOMPLETE_WINDOW','WINDOW_INCOMPLETE'),('INVALID_RESPONSE','INVALID_SAMPLE'),('UNIT_CHANGED','SOURCE_CHANGED'),('SOURCE_KEY_CHANGED','SOURCE_CHANGED'),('VALUE_TYPE_CHANGED','SOURCE_CHANGED'),('METADATA_CHANGED','SOURCE_CHANGED'),('ACCESS_DENIED','FORBIDDEN')])
def test_source_error_does_not_claim_record_validation(reason,error):
 v=stored();o=v['observation'];o.update(kind='LOG_STREAM', **{'from':'2026-10-04T00:00:00Z','till':'2026-10-04T00:01:00Z','startedAt':'2026-10-04T00:01:10Z','completedAt':'2026-10-04T00:01:11Z'});v['entityIds']=[];o['state']='SOURCE_FAILED';o['error']=error;o['result']={'coverage':'UNAVAILABLE','sampleRate':None,'received':None,'accepted':None,'rejected':None,'filtered':None,'unknown':None,'schemaMismatch':None,'missingIdentity':None,'invalidTimestamp':None,'unitMismatch':None,'nodes':[]};o['sourceRead'].update(completed=0,failed=1,received=None,failureCode=reason,startedAt=o['startedAt'],completedAt=o['completedAt']);validate('workflow-diagnostic-stored',v);o['error']='OUTPUT_REJECTED'
 with pytest.raises(ValidationError):validate('workflow-diagnostic-stored',v)

@pytest.mark.parametrize('reason',['UNIT_CHANGED','SOURCE_KEY_CHANGED','VALUE_TYPE_CHANGED'])
def test_host_page_cannot_claim_metric_metadata_failure(reason):
 v=stored();o=v['observation'];o.update(state='SOURCE_FAILED',error='SOURCE_CHANGED');o['result']={'coverage':'UNAVAILABLE','sampleRate':None,'received':None,'accepted':None,'rejected':None,'filtered':None,'unknown':None,'schemaMismatch':None,'missingIdentity':None,'invalidTimestamp':None,'unitMismatch':None,'nodes':[]};o['sourceRead'].update(completed=0,failed=1,received=None,failureCode=reason)
 with pytest.raises(ValidationError):validate('workflow-diagnostic-stored',v)
