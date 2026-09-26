import json
from pathlib import Path
import pytest
from jsonschema import ValidationError
from test_pipeline import validate

ROOT = Path(__file__).resolve().parents[2]
def sample(name):
    return json.loads((ROOT / f'contracts/examples/{name}.json').read_text())

@pytest.mark.parametrize('name',['source-scan-run','source-scan-run-page','source-scan-run-read'])
def test_published_examples_conform(name):
    validate(name,sample(name))

@pytest.mark.parametrize('field',['permissions','token','scanConsistency','requestKey','vendor'])
def test_trace_never_echoes_client_authority_or_unknown_fields(field):
    body=sample('source-scan-run-page');body[field]='override'
    with pytest.raises(ValidationError):validate('source-scan-run-page',body)

@pytest.mark.parametrize('field,value',[('dataMode','live'),('dataMode','import'),('storage','victim-store'),('limit',0),('limit',51),('after','not a cursor'),('nextCursor','***'),('hasMore','true'),('objectType','problem')])
def test_trace_page_stays_bounded_and_never_claims_a_live_source(field,value):
    body=sample('source-scan-run-page');body[field]=value
    with pytest.raises(ValidationError):validate('source-scan-run-page',body)

def test_trace_page_caps_items_at_the_documented_bound():
    body=sample('source-scan-run-page');body['items']=body['items']*26
    with pytest.raises(ValidationError):validate('source-scan-run-page',body)

@pytest.mark.parametrize('status,snapshotComplete,completedAt',[('FAILED',True,None),('RUNNING',True,None),('SUCCEEDED',False,None),('SUCCEEDED',True,None),('RUNNING',False,'2026-09-26T05:10:00Z'),('FAILED',False,None)])
def test_a_run_cannot_claim_completion_it_did_not_reach(status,snapshotComplete,completedAt):
    body=sample('source-scan-run');body['status']=status;body['snapshotComplete']=snapshotComplete;body['completedAt']=completedAt
    with pytest.raises(ValidationError):validate('source-scan-run',body)

@pytest.mark.parametrize('value',['COMPLETED','live','succeeded',''])
def test_run_status_is_a_closed_enum(value):
    body=sample('source-scan-run');body['status']=value
    with pytest.raises(ValidationError):validate('source-scan-run',body)

@pytest.mark.parametrize('value',['live','fixture','closed-scan',''])
def test_run_data_mode_is_a_closed_enum(value):
    body=sample('source-scan-run');body['dataMode']=value
    with pytest.raises(ValidationError):validate('source-scan-run',body)

@pytest.mark.parametrize('value',['SOURCE_FETCH_FAILED_LATER','source_fetch_failed',''])
def test_failure_code_is_one_of_the_stored_codes(value):
    body=sample('source-scan-run');body['failureCode']=value
    with pytest.raises(ValidationError):validate('source-scan-run',body)

def test_an_unverified_walk_is_a_stored_failure_code():
    body=sample('source-scan-run')
    body['failureCode']='SOURCE_SCAN_UNVERIFIED'
    body['failureSummary']='The scan ended without a verified snapshot. Existing entities were kept and nothing was reconciled.'
    validate('source-scan-run',body)

def test_failure_summary_cannot_carry_raw_multiline_text():
    body=sample('source-scan-run');body['failureSummary']='line one\nline two'
    with pytest.raises(ValidationError):validate('source-scan-run',body)

@pytest.mark.parametrize('field,value',[('digest','sha256:not-a-digest'),('revision',0),('id','1-invalid')])
def test_pinned_mapping_version_must_be_a_real_ref(field,value):
    body=sample('source-scan-run');body['pipelineVersion'][field]=value
    with pytest.raises(ValidationError):validate('source-scan-run',body)

def test_read_response_requires_the_stored_run():
    body=sample('source-scan-run-read');del body['run']
    with pytest.raises(ValidationError):validate('source-scan-run-read',body)
    body=sample('source-scan-run-read');body['run']['objectType']='problem'
    with pytest.raises(ValidationError):validate('source-scan-run-read',body)

@pytest.mark.parametrize('field,value',[('syncRunId','not-a-uuid'),('pages',-1),('fetched',9007199254740992),('cursor','x'*257),('snapshotComplete','false'),('scanConsistency','live'),('scanConsistency','hostid-watermark-snapshot\n')])
def test_run_fields_stay_inside_the_stored_ranges(field,value):
    body=sample('source-scan-run');body[field]=value
    with pytest.raises(ValidationError):validate('source-scan-run',body)

@pytest.mark.parametrize('label',['offset-scan-attempt','hostid-watermark-snapshot','itemid-watermark-snapshot'])
def test_a_stored_run_keeps_how_it_was_bounded(label):
    body=sample('source-scan-run');body['scanConsistency']=label
    validate('source-scan-run',body)

def test_a_stored_run_must_state_how_it_was_bounded():
    body=sample('source-scan-run');del body['scanConsistency']
    with pytest.raises(ValidationError):validate('source-scan-run',body)

def test_an_item_run_keeps_its_own_bound():
    body=sample('source-scan-run')
    body['objectType']='item'
    body.pop('pipelineVersion',None)
    body['scanConsistency']='itemid-watermark-snapshot'
    body['failureCode']='SOURCE_SCAN_UNVERIFIED'
    body['failureSummary']='The scan ended without a verified snapshot. Existing entities were kept and nothing was reconciled.'
    validate('source-scan-run',body)
    body['scanConsistency']='hostid-watermark-snapshot'
    validate('source-scan-run',body)
    body['scanConsistency']='consistent-snapshot'
    with pytest.raises(ValidationError):validate('source-scan-run',body)
