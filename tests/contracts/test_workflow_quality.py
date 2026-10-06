import copy
import pytest
from jsonschema import ValidationError
from test_workflow import validate

def batch():
    return dict(id='10000000-0000-4000-8000-000000000125',observedAt='2026-10-05T00:01:10Z',updatedAt='2026-10-05T00:01:10Z',**{'from':'2026-10-05T00:00:00Z','till':'2026-10-05T00:01:00Z'},unit='LOG_RECORD',state='UNKNOWN',coverage='WINDOW',sampleRate=None,counts=dict(input=2,accepted=2,rejected=0,filtered=0,deduplicated=0,outputExpected=2,confirmed=0,outputRejected=0,unknown=2,pending=0,repeatedOutput=0,late=0),error='OUTPUT_UNCONFIRMED',reconcilesBatchId=None)

@pytest.mark.parametrize('patch',[{'body':'private'},{'entityIds':[]},{'authority':{}},{'sampleRate':1},{'coverage':'COMPLETE'},{'state':'SUCCEEDED'},{'error':'raw customer exception'},{'id':'1-1-1-1-1'},{'unit':'ENTITY'},{'state':'IN_FLIGHT'},{'from':None}])
def test_closed_batch_rejects_invented_measurements_and_private_values(patch):
    b=batch();b.update(patch)
    with pytest.raises(ValidationError):validate('workflow-quality-batch',b)

def test_unknown_is_measured_without_claiming_success():
    b=batch();validate('workflow-quality-batch',b)
    b.update(state='CONFIRMED',error=None);b['counts'].update(confirmed=2,unknown=0);validate('workflow-quality-batch',b)

def test_host_partial_confirmation_has_no_transform_or_output_denominator():
    b=batch();b.update(unit='ENTITY',coverage='PAGE',error='OUTPUT_UNAVAILABLE',**{'from':None,'till':None})
    b['counts']={k:None for k in b['counts']};b['counts'].update(input=2,confirmed=1);validate('workflow-quality-batch',b)
    b['counts']['input']=None
    with pytest.raises(ValidationError):validate('workflow-quality-batch',b)

@pytest.mark.parametrize('patch',[{'unknown':1001},{'confirmed':'2'},{'raw':'private'},{'sampleRate':0.8},{'input':None}])
def test_counter_boundary(patch):
    c=batch()['counts'];c.update(patch)
    with pytest.raises(ValidationError):validate('workflow-quality-counts',c)

def test_report_is_version_scoped_and_bounded():
    r=dict(schemaVersion='2.0',asOf='2026-10-05T00:02:00Z',reference=dict(id='quality-fixture',revision=1,digest='sha256:'+'a'*64),kind='LOG_STREAM',task=None,batches=[batch()],truncated=False);validate('workflow-quality-report',r)
    for patch in [{'batches':[batch()]*21},{'tenantId':'forged'},{'kind':'PREVIEW'},{'truncated':True}]:
        bad=copy.deepcopy(r);bad.update(patch)
        with pytest.raises(ValidationError):validate('workflow-quality-report',bad)
