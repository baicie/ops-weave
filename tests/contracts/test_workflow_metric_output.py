import copy
import json
from pathlib import Path
import pytest
from jsonschema import ValidationError
from test_workflow import validate
ROOT=Path(__file__).resolve().parents[2]

def receipt():return json.loads((ROOT/'contracts/examples/v2/workflow-metric-output-receipt.json').read_text(encoding='utf-8'))

def test_metric_output_has_only_batch_metadata_and_explicit_unknown_range():
    x=receipt();validate('workflow-metric-output-receipt',x)
    assert x['workflowId'].startswith('fixture-')
    assert 'samples' not in x and 'values' not in x
    x.update(state='CONFIRMED',confirmed=1,unknown=0,error=None);validate('workflow-metric-output-receipt',x)
    x.update(state='FAILED',confirmed=0,failed=1,error='OUTPUT_REJECTED');validate('workflow-metric-output-receipt',x)

@pytest.mark.parametrize('patch',[dict(tenantId='forged'),dict(samples=[dict(value='private')]),dict(state='RETRY'),dict(confirmed=1),dict(unknown=6),dict(error=None),dict(batchDigest='bad')])
def test_metric_output_rejects_forged_or_contradictory_receipts(patch):
    x=receipt();x.update(patch)
    with pytest.raises(ValidationError):validate('workflow-metric-output-receipt',x)

def test_metric_output_requires_original_preview_and_closed_raw_sample():
    x=receipt();request=dict(requestId=x['requestId'],id=x['workflowId'],revision=1,digest=x['digest'],previewId=x['previewId'],samples=[dict(timestamp=x['createdAt'],sourceKey='system.cpu.util[,user]',value='12.5')])
    validate('workflow-metric-output-request',request)
    for key,value in [('tenantId','forged'),('authority',{}),('endpoint','http://127.0.0.1:9')]:
        with pytest.raises(ValidationError):validate('workflow-metric-output-request',{**request,key:value})
    for patch in [dict(value=12.5),dict(extra='private'),dict(timestamp='bad')]:
        candidate=copy.deepcopy(request);candidate['samples'][0].update(patch)
        with pytest.raises(ValidationError):validate('workflow-metric-output-request',candidate)
    request['samples']*=6
    with pytest.raises(ValidationError):validate('workflow-metric-output-request',request)

def test_metric_output_queries_are_bounded_and_do_not_claim_complete_empty_data():
    x=receipt();data=dict(requestId=x['requestId'],queriedAt=x['updatedAt'],dataMode='time-series',expectedPoints=1,proofMatches=False,points=[])
    validate('workflow-metric-output-points',data)
    data['points']=[dict(timestampMillis=x['timestamps'][0],value='0.125')];data['proofMatches']=True;validate('workflow-metric-output-points',data)
    data['points']*=6
    with pytest.raises(ValidationError):validate('workflow-metric-output-points',data)
    validate('workflow-metric-output-capability',dict(schemaVersion='2.0',mode='FIXED_METRIC_SAMPLE',available=False,maxPoints=5))

def test_metric_output_history_is_owner_bounded_and_closed():
    x=dict(items=[receipt()],truncated=False);validate('workflow-metric-output-history',x)
    with pytest.raises(ValidationError):validate('workflow-metric-output-history',{**x,'owner':'forged'})
    with pytest.raises(ValidationError):validate('workflow-metric-output-history',dict(items=[receipt()]*21,truncated=True))

def test_metric_output_point_decimal_is_bounded_without_exponent_or_private_fields():
    x=receipt();data=dict(requestId=x['requestId'],queriedAt=x['updatedAt'],dataMode='time-series',expectedPoints=1,proofMatches=True,points=[dict(timestampMillis=x['timestamps'][0],value='0.'+'0'*63+'1')])
    validate('workflow-metric-output-points',data)
    for value in ['1e-3','NaN','1'*401]:
        data['points'][0]['value']=value
        with pytest.raises(ValidationError):validate('workflow-metric-output-points',data)
