import copy
import json
from pathlib import Path
import pytest
from jsonschema import Draft202012Validator, FormatChecker, ValidationError
from referencing import Registry, Resource
ROOT = Path(__file__).resolve().parents[2]
REGISTRY = Registry().with_resources((p.as_uri(), Resource.from_contents(json.loads(p.read_text(encoding='utf-8')))) for p in (ROOT/'contracts/schemas').rglob('*.schema.json'))
def validate(name, value):
    Draft202012Validator({'$ref': (ROOT/'contracts/schemas/v2'/f'{name}.schema.json').as_uri()},registry=REGISTRY,format_checker=FormatChecker()).validate(value)
def example(): return json.loads((ROOT/'contracts/examples/v2/workflow-definition.json').read_text(encoding='utf-8'))
def test_fixture_example_and_all_closed_node_configs():
    d=example(); validate('workflow-definition',d)
    for kind,config in [('EMPTY_TO_NULL',{'field':'name'}),('DEFAULT',{'field':'port','value':'8080'}),('ENUM_MAP',{'field':'name','from':'old','to':'new'}),('SCALE',{'field':'port','factor':'0.01'}),('FILTER',{'field':'name','equals':'a'})]:
        d['nodes'][2]['type']=kind;d['nodes'][2]['config']=config;validate('workflow-definition',d)
@pytest.mark.parametrize('patch',[{'schemaVersion':'1.0'},{'tenantId':'forged'},{'revision':0},{'revision':1.5},{'id':'../escape'},{'target':{'id':'custom.missing','revision':1}},{'source':{'kind':'HTTP','instanceId':'http://remote'}},{'source':{'kind':'MANUAL_SAMPLE','instanceId':'zabbix-local'}}])
def test_definition_rejects_unsupported_fields_and_versions(patch):
    d=example();d.update(patch)
    with pytest.raises(ValidationError):validate('workflow-definition',d)
@pytest.mark.parametrize('patch',[{'type':'CODE'},{'version':'latest'},{'config':{'script':'x'}},{'id':'../../x'}])
def test_node_boundary(patch):
    d=example();d['nodes'][2].update(patch)
    with pytest.raises(ValidationError):validate('workflow-definition',d)
@pytest.mark.parametrize('field',['tenantId','tenant_id','authorization','constructor','password'])
def test_mapping_cannot_override_protected_fields(field):
    d=example();d['nodes'][1]['config']={'name':field}
    with pytest.raises(ValidationError):validate('workflow-definition',d)
def test_graph_and_scalar_budgets():
    d=example();d['nodes']*=4
    with pytest.raises(ValidationError):validate('workflow-definition',d)
    d=example();d['nodes'][1]['config']={f'f{i}':f't{i}' for i in range(33)}
    with pytest.raises(ValidationError):validate('workflow-definition',d)

def test_dag_merge_is_closed_and_edge_budget_is_bounded():
    d=example();d['nodes'][2]['type']='MERGE';d['nodes'][2]['config']={};validate('workflow-definition',d)
    d['nodes'][2]['config']={'script':'must not execute'}
    with pytest.raises(ValidationError):validate('workflow-definition',d)
    d=example();d['edges']*=12
    with pytest.raises(ValidationError):validate('workflow-definition',d)

def trace_example(): return json.loads((ROOT/'contracts/examples/v2/workflow-run-detail.json').read_text(encoding='utf-8'))
def test_run_trace_and_legacy_summary():
    d=trace_example();validate('workflow-run-detail',d)
    d['trace']=None;validate('workflow-run-detail',d)
@pytest.mark.parametrize('patch',[{'values':{'private':'must not persist'}},{'status':'DONE'},{'issues':[{'field':'port','code':'STACK_TRACE'}]},{'issues':[{'field':'port','code':'TYPE_MISMATCH','message':'private text'}]}])
def test_trace_does_not_accept_values_or_unbounded_errors(patch):
    d=trace_example();d['trace']['rows'][0]['steps'][0].update(patch)
    with pytest.raises(ValidationError):validate('workflow-run-detail',d)
@pytest.mark.parametrize('patch',[{'tenantId':'forged'},{'writesPerformed':True},{'durationMillis':-1},{'rows':[]},{'syncRunId':'not-a-uuid'}])
def test_trace_boundaries(patch):
    d=trace_example();d['trace'].update(patch)
    with pytest.raises(ValidationError):validate('workflow-run-detail',d)

@pytest.mark.parametrize('kind', ['log', 'metric'])
def test_typed_output_examples_and_trace(kind):
    d=json.loads((ROOT/f'contracts/examples/v2/workflow-{kind}-definition.json').read_text(encoding='utf-8'))
    validate('workflow-definition',d)
    record=json.loads((ROOT/f'contracts/examples/v2/workflow-{kind}-record.json').read_text(encoding='utf-8'))
    validate(f'workflow-{kind}-record',record)
    trace=trace_example();trace['trace']['target']=d['target'];validate('workflow-run-detail',trace)
    d['source']={'kind':'ZABBIX_HOST','instanceId':'fixture-host'}
    with pytest.raises(ValidationError):validate('workflow-definition',d)
    trace['trace']['source']=d['source']
    with pytest.raises(ValidationError):validate('workflow-run-detail',trace)

@pytest.mark.parametrize('target', [
    {'kind':'LOG','schemaVersion':'1.0','id':'builtin.host'},
    {'kind':'ENTITY','schemaVersion':'1.0'},
    {'kind':'LOG','schemaVersion':'latest'},
    {'kind':'UNKNOWN','schemaVersion':'1.0'},
])
def test_output_union_is_closed(target):
    d=example();d['target']=target
    with pytest.raises(ValidationError):validate('workflow-definition',d)

@pytest.mark.parametrize('kind,field', [('log','hostname'),('metric','body')])
def test_telemetry_mapping_cannot_target_entity_or_other_output_fields(kind,field):
    d=json.loads((ROOT/f'contracts/examples/v2/workflow-{kind}-definition.json').read_text(encoding='utf-8'))
    d['nodes'][1]['config']={'source':field}
    with pytest.raises(ValidationError):validate('workflow-definition',d)

@pytest.mark.parametrize('kind,patch', [
    ('log',{'eventTime':'missing timezone'}),('log',{'body':'  '}),
    ('log',{'entityId':'forged'}),('log',{'traceId':'invalid'}),
    ('metric',{'value':'0.5'}),('metric',{'metricType':'SUM'}),
    ('metric',{'timestamp':'not-time'}),('metric',{'metricKey':' '}),
])
def test_normalized_telemetry_record_boundaries(kind,patch):
    r=json.loads((ROOT/f'contracts/examples/v2/workflow-{kind}-record.json').read_text(encoding='utf-8'));r.update(patch)
    with pytest.raises(ValidationError):validate(f'workflow-{kind}-record',r)
