import copy
import json
from pathlib import Path
import pytest
from jsonschema import Draft202012Validator, FormatChecker, ValidationError
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = Registry().with_resources((p.as_uri(), Resource.from_contents(json.loads(p.read_text(encoding='utf-8')))) for p in (ROOT/'contracts/schemas').rglob('*.schema.json'))
def validate(name, value):
    Draft202012Validator({'$ref': (ROOT/'contracts/schemas/v2'/f'{name}.schema.json').as_uri()}, registry=REGISTRY, format_checker=FormatChecker()).validate(value)
def example(name):
    return json.loads((ROOT/f'contracts/examples/v2/{name}.json').read_text(encoding='utf-8'))

def test_standard_target_record_and_trace_examples():
    definition = example('workflow-standard-metric-definition')
    validate('workflow-definition', definition)
    validate('workflow-standard-metric-record', example('workflow-standard-metric-record'))
    trace = example('workflow-run-detail')
    trace['trace']['target'] = definition['target']
    validate('workflow-run-detail', trace)

@pytest.mark.parametrize('patch', [
    {'kind':'LOG'}, {'schemaVersion':'1.0'}, {'metricKey':'../escape'}, {'metricKey':'x'*129},
    {'mappingPin':None}, {'mappingPin':{'id':'cpu','revision':0,'digest':'sha256:'+'a'*64}},
    {'mappingPin':{'id':'cpu','revision':1,'digest':'wrong'}}, {'tenantId':'forged'}, {'unit':'forged'},
])
def test_standard_target_is_closed_and_versioned(patch):
    d = example('workflow-standard-metric-definition'); d['target'].update(patch)
    with pytest.raises(ValidationError): validate('workflow-definition', d)

def test_standard_target_requires_key_and_pin_and_real_source_is_not_faked():
    for key in ['metricKey','mappingPin']:
        d = example('workflow-standard-metric-definition'); del d['target'][key]
        with pytest.raises(ValidationError): validate('workflow-definition', d)
    d = example('workflow-standard-metric-definition'); d['source']={'kind':'ZABBIX_HOST','instanceId':'fixture-host'}
    with pytest.raises(ValidationError): validate('workflow-definition', d)

@pytest.mark.parametrize('patch', [
    {'value':0.125}, {'value':'NaN'}, {'value':'1e0'}, {'value':'1'*65}, {'unit':None},
    {'metricType':'SUM'}, {'dimensions':{'mode':{'nested':'forged'}}}, {'dimensions':{'x'*65:'v'}},
    {'mappingPin':{'id':'cpu','revision':1,'digest':'sha256:'+'a'*64,'tenantId':'forged'}},
    {'sourceKey':'must not persist'}, {'timestamp':'bad time'}, {'metricKey':''},
])
def test_normalized_record_has_exact_decimal_and_sealed_metadata(patch):
    r = example('workflow-standard-metric-record'); r.update(patch)
    with pytest.raises(ValidationError): validate('workflow-standard-metric-record',r)

def result_example():
    d=example('workflow-standard-metric-definition'); receipt=example('workflow-run-detail')['run']['receipt']
    receipt.update(accepted=1,rejected=0,filtered=0)
    record=example('workflow-standard-metric-record')
    steps=[{'nodeId':n['id'],'type':n['type'],'status':'OK','values':copy.deepcopy(record) if n['type'] in ['VALIDATE','OUTPUT'] else {'value':'12.5'},'issues':[]} for n in d['nodes']]
    return {'receipt':receipt,'evaluation':{'rows':[{'index':0,'status':'ACCEPTED','steps':steps}], 'accepted':1,'rejected':0,'filtered':0,'dryRun':True,'writesPerformed':False},'retainedCount':1,'missingRaw':0,'truncated':False,'sourceStatus':'MANUAL_SAMPLE'}

def test_nested_standard_metadata_only_in_successful_normalized_steps():
    r=result_example(); validate('workflow-result',r)
    for index in [0,1]:
        bad=copy.deepcopy(r);bad['evaluation']['rows'][0]['steps'][index]['values']=example('workflow-standard-metric-record')
        with pytest.raises(ValidationError): validate('workflow-result',bad)
    for status in ['ERROR','SKIPPED','FILTERED']:
        bad=copy.deepcopy(r);bad['evaluation']['rows'][0]['steps'][2]['status']=status
        with pytest.raises(ValidationError): validate('workflow-result',bad)

def test_mapping_type_uses_the_existing_telemetry_sum_name():
    d=example('metric-mapping-definition');d['metricType']='SUM';validate('metric-mapping-definition',d)
    d['metricType']='COUNTER'
    with pytest.raises(ValidationError):validate('metric-mapping-definition',d)
