import copy
import importlib.util
import json
from pathlib import Path
import pytest
from jsonschema import Draft202012Validator, ValidationError
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('workflow_generator', ROOT / 'scripts/generate_workflow_operators.py')
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)


def catalog():
    return json.loads(generator.CATALOG.read_text(encoding='utf-8'))


def validator(name):
    registry = Registry()
    for path in (ROOT / 'contracts/schemas').rglob('*.json'):
        schema = json.loads(path.read_text(encoding='utf-8'))
        if '$id' in schema:
            registry = registry.with_resource(schema['$id'], Resource.from_contents(schema))
    schema = json.loads((ROOT / 'contracts/schemas/v2' / (name + '.schema.json')).read_text(encoding='utf-8'))
    return Draft202012Validator(schema, registry=registry)


def test_catalog_is_closed_and_generated_outputs_are_current():
    value = catalog()
    validator('workflow-operator-catalog').validate(value)
    generator.validate(value)
    for path, body in generator.outputs(value).items():
        assert path.read_text(encoding='utf-8') == body
    assert len(value['operators']) == len({o['type'] for o in value['operators']}) == 11


@pytest.mark.parametrize('mutation', ['extra', 'remote', 'unknown_type', 'unknown_parameter', 'port_kind', 'unbounded_port', 'null_digest'])
def test_catalog_rejects_untrusted_execution_metadata(mutation):
    value = catalog()
    if mutation == 'extra': value['tenantId'] = 'forged'
    elif mutation == 'remote': value['operators'][0]['implementation'] = 'https://example.invalid/download.jar'
    elif mutation == 'unknown_type': value['operators'][0]['type'] = 'SHELL'
    elif mutation == 'unknown_parameter': value['operators'][4]['parameters'][0]['kind'] = 'CODE'
    elif mutation == 'port_kind': value['operators'][0]['output']['kind'] = 'HTTP'
    elif mutation == 'unbounded_port': value['operators'][0]['output']['maximum'] = 1000
    else: value['operators'][0]['digest'] = None
    with pytest.raises(ValidationError): validator('workflow-operator-catalog').validate(value)


@pytest.mark.parametrize('mutation', ['digest', 'version', 'duplicates', 'ports'])
def test_generator_rejects_catalog_drift(mutation):
    value = catalog()
    if mutation == 'digest': value['operators'][2]['digest'] = 'sha256:' + '0' * 64
    elif mutation == 'version': value['operators'][2]['version'] = '2'
    elif mutation == 'duplicates': value['operators'][2] = copy.deepcopy(value['operators'][3])
    else: value['operators'][0]['output']['maximum'] = 2
    with pytest.raises(ValueError): generator.validate(value)


def test_display_text_does_not_change_operator_semantics_but_ports_do():
    value = catalog()['operators'][2]
    assert generator.digest(generator.semantic_parts(value)) == value['digest']
    renamed = {**value, 'label': '另一显示名称', 'hint': '另一显示说明'}
    assert generator.semantic_parts(renamed) == generator.semantic_parts(value)
    changed = copy.deepcopy(value)
    changed['input']['kind'] = 'VALIDATED_RECORD_V1'
    assert generator.digest(generator.semantic_parts(changed)) != value['digest']


def test_legacy_and_pinned_definitions_are_both_structurally_valid():
    value = json.loads((ROOT / 'contracts/examples/v2/workflow-definition.json').read_text(encoding='utf-8'))
    validator('workflow-definition').validate(value)
    operators = {o['type']: o for o in catalog()['operators']}
    for node in value['nodes']: node['operatorDigest'] = operators[node['type']]['digest']
    validator('workflow-definition').validate(value)
    assert value == json.loads((ROOT / 'contracts/examples/v2/workflow-pinned-definition.json').read_text(encoding='utf-8'))
    value['nodes'][0]['operatorDigest'] = None
    with pytest.raises(ValidationError): validator('workflow-definition').validate(value)


def test_input_and_exit_ports_are_typed_and_merge_preserves_legacy_single_input():
    operators = {o['type']: o for o in catalog()['operators']}
    assert operators['SOURCE']['input'] is None
    assert operators['SOURCE']['output']['maximum'] == 1
    assert operators['VALIDATE']['output']['kind'] == operators['OUTPUT']['input']['kind'] == 'VALIDATED_RECORD_V1'
    assert operators['OUTPUT']['output'] is None
    assert operators['MERGE']['input']['minimum'] == 1


def test_stale_operator_task_has_a_specific_closed_error():
    value = dict(workflowId='fixture-task',revision=1,digest='sha256:'+'a'*64,
                 settings=dict(identityField='entity_id',nameField='name'),generation=1,state='FAILED',
                 cursor='2026-10-03T00:00:00Z',cursorId='10000000-0000-4000-8000-000000000071',
                 updatedAt='2026-10-03T00:00:00Z',error='OPERATOR_CHANGED')
    validator('workflow-runtime-task').validate(value)
    value['error'] = 'OPERATOR_PIN_REQUIRED'
    with pytest.raises(ValidationError): validator('workflow-runtime-task').validate(value)
