import copy
import json
from pathlib import Path
import pytest
from jsonschema import Draft202012Validator, FormatChecker, ValidationError
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / 'contracts/schemas/v1'
REGISTRY = Registry().with_resources((p.as_uri(), Resource.from_contents(json.loads(p.read_text(encoding='utf-8')))) for p in (ROOT / 'contracts/schemas').rglob('*.schema.json'))

def validate(name, value):
    Draft202012Validator({'$ref': (SCHEMAS / (name + '.schema.json')).as_uri()}, registry=REGISTRY, format_checker=FormatChecker()).validate(value)

def example():
    return json.loads((ROOT / 'contracts/examples/model-definition.json').read_text(encoding='utf-8'))

def test_builtin_package_definitions_and_pinned_relationships():
    package = json.loads((ROOT / 'contracts/catalog/opsweave-core-1.0.0.json').read_text(encoding='utf-8'))
    validate('model-catalog-package', package)
    entities = {d['id'] for d in package['definitions'] if d['kind'] == 'ENTITY'}
    assert len(entities) == 5
    for definition in package['definitions']:
        validate('model-definition', definition)
        if definition['kind'] == 'RELATION':
            assert definition['endpoints']['from']['id'] in entities
            assert definition['endpoints']['to']['id'] in entities
    assert package['connectorPolicy']['otherVersions'] == 'UNVERIFIED'
    assert {m['key'] for m in package['metrics']} == {'host.cpu.usage.user', 'host.memory.available.ratio', 'host.system.uptime'}

@pytest.mark.parametrize('patch', [ {'tenantId':'forged'}, {'schemaVersion':'2.0'}, {'cleaningProfile':'javascript'}, {'id':'builtin/../../escape'}, {'revision':0}, {'revision':1.5}, {'kind':'SCRIPT'}, {'endpoints':None} ])
def test_model_rejects_overrides_unknown_rules_and_paths(patch):
    value = example(); value.update(patch)
    with pytest.raises(ValidationError): validate('model-definition', value)

@pytest.mark.parametrize('patch', [ {'id':'tenant_id'}, {'id':'constructor'}, {'type':'SQL'}, {'type':'BOOLEAN'}, {'required':'yes'}, {'maxLength':2049}, {'choices':['fake']}, {'min':0} ])
def test_field_contract_rejects_unsafe_or_inapplicable_constraints(patch):
    value = example(); value['fields'][0].update(patch)
    with pytest.raises(ValidationError): validate('model-definition', value)

def test_relation_requires_pinned_entities_and_no_instance_payload():
    value = example(); value.update(kind='RELATION', fields=[])
    with pytest.raises(ValidationError): validate('model-definition', value)
    value['endpoints'] = {'from':{'id':'builtin.service','revision':1}, 'to':{'id':'builtin.host','revision':1}, 'cardinality':'MANY_TO_MANY'}
    validate('model-definition', value)
    value['endpoints']['from']['entityId']='not-a-definition'
    with pytest.raises(ValidationError): validate('model-definition', value)

def test_preview_distinguishes_missing_null_and_invalid_values():
    value = {'valid':False, 'values':{'name':None}, 'issues':[{'field':'name','code':'NULL_REQUIRED'}, {'field':'port','code':'MISSING_REQUIRED'}], 'changes':[]}
    validate('model-preview', value)
    value['issues'][0]['rawCustomerLog']='not-allowed'
    with pytest.raises(ValidationError): validate('model-preview', value)

def test_entry_draft_edit_version_is_separate_from_immutable_version():
    value = {'definition':example(), 'digest':'sha256:'+'a'*64, 'state':'DRAFT', 'editVersion':1, 'updatedAt':'2026-09-27T10:00:00Z'}
    validate('model-catalog-entry', value)
    value['state']='PUBLISHED'
    with pytest.raises(ValidationError): validate('model-catalog-entry', value)
    value['editVersion']=0
    validate('model-catalog-entry', value)
