import copy
import pytest
from jsonschema import ValidationError
from test_source_inspections import sample,validate

@pytest.mark.parametrize('name',['source-metric-discovery','source-metric-discovery-read'])
def test_explicit_synthetic_metadata(name): validate(name,sample(name))

@pytest.mark.parametrize('patch',[{'scope':'ALL_ITEMS'},{'limit':21},{'complete':True,'scanConsistency':'UNVERIFIED'},{'complete':False,'statusCode':'READ_VERIFIED'},{'statusCode':'UNREACHABLE'},{'cursor':'next'},{'items':[]}])
def test_metadata_scope_is_closed(patch):
    value=sample('source-metric-discovery'); first=copy.deepcopy(value['items'][0]); value.update(patch)
    if patch=={'items':[]}: value['items']=[copy.deepcopy(first) for _ in range(21)]
    with pytest.raises(ValidationError): validate('source-metric-discovery',value)

@pytest.mark.parametrize('patch',[{'sourceValueType':'NUMBER'},{'itemId':'01'},{'hostId':'../x'},{'sourceKey':'x'*2049},{'sourceUnit':'x'*65},{'name':'x\n'},{'mappingStatus':'SUPPORTED'},{'mapping':None},{'lastvalue':'1'},{'Authorization':'forged'}])
def test_rows_never_carry_values_or_secret_fields(patch):
    value=sample('source-metric-discovery');value['items'][0].update(patch)
    with pytest.raises(ValidationError):validate('source-metric-discovery',value)

def test_result_exclusivity_and_original_host_receipt_compatibility():
    validate('source-inspection-read',sample('source-inspection-read'))
    value=sample('source-metric-discovery-read')['view']['inspection']
    for patch in [{'metricDiscovery':None},{'discovery':sample('source-inspection-read')['view']['inspection']['discovery']},{'kind':'TEST'},{'state':'UNKNOWN'}]:
        with pytest.raises(ValidationError):validate('source-inspection',{**value,**patch})
