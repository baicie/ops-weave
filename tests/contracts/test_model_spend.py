import json
import pytest
from jsonschema import ValidationError
from test_entity_page import ROOT, validate

def example(name): return json.loads((ROOT / f'contracts/examples/{name}.json').read_text())

@pytest.mark.parametrize('field,value', [('tenantId','other'),('provider','rig-openai'),('dailyMicros',999999),('inputBytes',0),('inputBytes',73729),('inputDigest','invented'),('inputDigest','sha256:'+'a'*64+'\n'),('runId','9d4df4eb-6d34-43ce-a135-6f7d635fca21\n'),('runId','../other')])
def test_reservation_rejects_trust_overrides_and_bad_bounds(field,value):
    v=example('model-spend-reserve');v[field]=value
    with pytest.raises(ValidationError):validate('model-spend-reserve',v)

@pytest.mark.parametrize('field,value',[('inputTokens',81921),('outputTokens',2049),('source','estimated'),('inputTokens',-1),('source','mock-no-call')])
def test_usage_bounds_and_explicit_origin(field,value):
    v=example('model-spend-usage');v[field]=value
    with pytest.raises(ValidationError):validate('model-spend-usage',v)

@pytest.mark.parametrize('field,value',[('usage',None),('reportedAt',None),('estimatedMicros',None),('state','UNCERTAIN'),('currency','CNY'),('maxOutputTokens',2049),('accountedMicros',-1)])
def test_reported_record_cannot_hide_unknown_cost(field,value):
    v=example('model-spend-record');v[field]=value
    with pytest.raises(ValidationError):validate('model-spend-record',v)

def test_pending_receipt_must_remain_explicitly_unknown_and_input_only_usage_is_billable():
    v=example('model-spend-record');v.update(state='UNCERTAIN',usage=None,reportedAt=None,estimatedMicros=None,accountedMicros=v['reservedMicros']);validate('model-spend-record',v)
    v['estimatedMicros']=0
    with pytest.raises(ValidationError):validate('model-spend-record',v)
    u=example('model-spend-usage');u['outputTokens']=0;validate('model-spend-usage',u)
