import copy
import json
from pathlib import Path
import pytest
from jsonschema import Draft202012Validator, ValidationError, FormatChecker
from referencing import Registry, Resource
ROOT = Path(__file__).resolve().parents[2]
def sample(name): return json.loads((ROOT/'contracts/examples/v2'/f'{name}.json').read_text(encoding='utf-8'))
def validator(name):
    registry=Registry()
    for path in (ROOT/'contracts/schemas').rglob('*.json'):
        schema=json.loads(path.read_text(encoding='utf-8'))
        if '$id' in schema: registry=registry.with_resource(schema['$id'],Resource.from_contents(schema))
    return Draft202012Validator(json.loads((ROOT/'contracts/schemas/v2'/f'{name}.schema.json').read_text(encoding='utf-8')),registry=registry,format_checker=FormatChecker())
@pytest.mark.parametrize('name',['workflow-comparison-request','workflow-comparison'])
def test_explicit_fixture_comparison(name): validator(name).validate(sample(name))
@pytest.mark.parametrize('name',['workflow-comparison-request','workflow-comparison'])
@pytest.mark.parametrize('mutation',['tenant','extra_pin','bad_digest','unbounded_revision','draft_zero','published_edit','unknown_state'])
def test_comparison_rejects_identity_and_invalid_version_reference(name,mutation):
    value=sample(name)
    if mutation=='tenant': value['tenantId']='forged'
    elif mutation=='extra_pin': value['base']['owner']='forged'
    elif mutation=='bad_digest': value['base']['digest']='sha256:bad'
    elif mutation=='unbounded_revision': value['candidate']['revision']=10001
    elif mutation=='draft_zero': value['candidate']['editVersion']=0
    elif mutation=='published_edit': value['base']['editVersion']=1
    else: value['base']['state']='RUNNING'
    with pytest.raises(ValidationError):validator(name).validate(value)
@pytest.mark.parametrize('mutation',['unknown_section','node_missing','global_node','extra','too_many','oversized_value','null_key','bad_time','duplicate'])
def test_comparison_is_bounded_and_closed(mutation):
    value=sample('workflow-comparison')
    if mutation=='unknown_section':value['changes'][0]['section']='SQL'
    elif mutation=='node_missing':value['changes'][2]['nodeId']=None
    elif mutation=='global_node':value['changes'][0]['nodeId']='source'
    elif mutation=='extra':value['changes'][0]['code']='arbitrary'
    elif mutation=='too_many':value['changes']=[copy.deepcopy(value['changes'][0]) for _ in range(1201)]
    elif mutation=='oversized_value':value['changes'][0]['after']='x'*4097
    elif mutation=='null_key':value['changes'][0]['key']=None
    elif mutation=='bad_time':value['comparedAt']='not-a-time'
    else:value['changes'].append(copy.deepcopy(value['changes'][0]))
    with pytest.raises(ValidationError):validator('workflow-comparison').validate(value)
def test_identical_processing_content_allows_empty_changes():
    value=sample('workflow-comparison');value['changes']=[];validator('workflow-comparison').validate(value)
