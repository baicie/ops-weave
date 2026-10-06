import copy
import pytest
from jsonschema import ValidationError
from test_workflow_standard_metric import validate, example

def test_real_metric_source_examples_and_historical_trace():
    d=example('workflow-real-metric-definition');validate('workflow-definition',d)
    validate('workflow-source',d['source']);validate('workflow-metric-source-pin',d['source']['metric'])
    validate('workflow-metric-source-page',example('workflow-metric-source-page'))
    r=example('workflow-run-detail');r['trace'].update(source=d['source'],target=d['target'],syncRunId=None,sourceStatus='SUCCEEDED');r['run']['receipt']['origin']='zabbix-jsonrpc'
    validate('workflow-run-detail',r)

@pytest.mark.parametrize('patch',[{'inspectionId':'1-1-1-1-1'},{'itemId':'0'},{'itemId':'1'*21},{'hostId':'../host'},{'sourceKey':''},{'sourceKey':'x'*2049},{'sourceUnit':'x'*65},{'sourceValueType':'TEXT'},{'digest':'wrong'},{'tenantId':'forged'},{'value':'12.5'},{'sourceKey':'bad\nkey'}])
def test_series_pin_is_closed_and_metadata_only(patch):
    d=example('workflow-real-metric-definition');d['source']['metric'].update(patch)
    with pytest.raises(ValidationError):validate('workflow-definition',d)

@pytest.mark.parametrize('field',['configuration','metric'])
def test_metric_source_requires_both_pins(field):
    d=example('workflow-real-metric-definition');del d['source'][field]
    with pytest.raises(ValidationError):validate('workflow-definition',d)

@pytest.mark.parametrize('target',[{'kind':'METRIC','schemaVersion':'1.0'},{'kind':'LOG','schemaVersion':'1.0'},{'id':'builtin.host','revision':1,'digest':'sha256:'+'a'*64}])
def test_raw_metric_source_requires_standard_metric_target(target):
    d=example('workflow-real-metric-definition');d['target']=target
    with pytest.raises(ValidationError):validate('workflow-definition',d)

@pytest.mark.parametrize('kind',['MANUAL_SAMPLE','ZABBIX_HOST'])
def test_other_sources_cannot_carry_series_pin(kind):
    d=example('workflow-real-metric-definition');d['source']['kind']=kind
    with pytest.raises(ValidationError):validate('workflow-definition',d)

def test_selection_page_rejects_over_budget_and_untrusted_fields():
    p=example('workflow-metric-source-page')
    for field in ['tenantId','secret','url','values']:
        bad=copy.deepcopy(p);bad[field]='forged'
        with pytest.raises(ValidationError):validate('workflow-metric-source-page',bad)
    p['items']*=101
    with pytest.raises(ValidationError):validate('workflow-metric-source-page',p)
