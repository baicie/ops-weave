import copy
import json
from pathlib import Path
import pytest
from jsonschema import Draft202012Validator, FormatChecker, ValidationError
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = Registry().with_resources((p.as_uri(), Resource.from_contents(json.loads(p.read_text(encoding='utf-8')))) for p in (ROOT/'contracts/schemas').rglob('*.schema.json'))
def sample(name): return json.loads((ROOT/'contracts/examples/v2'/f'{name}.json').read_text(encoding='utf-8'))
def validate(name, value): Draft202012Validator({'$ref': (ROOT/'contracts/schemas/v2'/f'{name}.schema.json').as_uri()}, registry=REGISTRY, format_checker=FormatChecker()).validate(value)

@pytest.mark.parametrize('name', ['source-instance-page','source-instance-read','source-instance-create-command','source-instance-edit-command','source-instance-configurations'])
def test_explicit_fixtures(name): validate(name, sample(name))

@pytest.mark.parametrize('field', ['tenantId','userId','permission','url','credential','secretRef','execute','target'])
@pytest.mark.parametrize('name', ['source-instance-create-command','source-instance-edit-command'])
def test_commands_cannot_replace_trust_or_supply_remote_execution(name, field):
 with pytest.raises(ValidationError): validate(name, {**sample(name), field: 'forged'})

@pytest.mark.parametrize('patch', [{'name':' '},{'name':' trailing '},{'description':'x'*501},{'expectedEditVersion':0},{'expectedEditVersion':1001},{'state':'RUNNING'},{'connectionDigest':'bad'},{'requestId':'../x'}])
def test_edit_bounds(patch):
 with pytest.raises(ValidationError): validate('source-instance-edit-command', {**sample('source-instance-edit-command'), **patch})

@pytest.mark.parametrize('patch', [{'state':'STOPPED'},{'configurationRevision':101},{'editVersion':0},{'updatedAt':'bad'},{'dataMode':'MANUAL_SAMPLE'},{'source':{'kind':'HTTP','instanceId':'x'}}])
def test_instance_closed_semantics(patch):
 i=sample('source-instance-read')['instance'];i.update(patch)
 with pytest.raises(ValidationError): validate('source-instance',i)

def test_immutable_configuration_envelope_and_manual_kind():
 p=sample('source-instance-configurations');validate('source-instance-configurations',p)
 p['items'][0]['credential']='forged'
 with pytest.raises(ValidationError): validate('source-instance-configurations',p)
 i=sample('source-instance-read')['instance'];i.update(source={'kind':'MANUAL_SAMPLE','instanceId':'manual'},dataMode='MANUAL_SAMPLE');validate('source-instance',i)
 i['source']['instanceId']='remote'
 with pytest.raises(ValidationError): validate('source-instance',i)

def test_receipt_is_a_fixed_instance_result_and_page_is_bounded():
 i=sample('source-instance-read')['instance'];c=sample('source-instance-edit-command');i.update(name=c['name'],description=c['description'],editVersion=2)
 receipt={'schemaVersion':'2.0','receipt':{'requestId':c['requestId'],'sourceId':i['id'],'commandDigest':'sha256:'+'a'*64,'instance':i}}
 validate('source-instance-command-receipt',receipt)
 receipt['receipt']['instance']['execute']=True
 with pytest.raises(ValidationError): validate('source-instance-command-receipt',receipt)
 page=sample('source-instance-page');page['items']=[copy.deepcopy(page['items'][0])]*21
 with pytest.raises(ValidationError): validate('source-instance-page',page)
