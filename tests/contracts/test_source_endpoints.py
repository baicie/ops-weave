import copy,json
from pathlib import Path
import pytest
from jsonschema import Draft202012Validator,FormatChecker,ValidationError
from referencing import Registry,Resource
ROOT=Path(__file__).resolve().parents[2]
REGISTRY=Registry().with_resources((p.as_uri(),Resource.from_contents(json.loads(p.read_text(encoding='utf-8')))) for p in (ROOT/'contracts/schemas').rglob('*.schema.json'))
def sample(n):return json.loads((ROOT/'contracts/examples/v2'/f'{n}.json').read_text(encoding='utf-8'))
def validate(n,v):Draft202012Validator({'$ref':(ROOT/'contracts/schemas/v2'/f'{n}.schema.json').as_uri()},registry=REGISTRY,format_checker=FormatChecker()).validate(v)
@pytest.mark.parametrize('name',['source-endpoint','source-endpoint-pin','source-endpoint-page','source-endpoint-read','source-endpoint-registry'])
def test_closed_fixture_examples(name):validate(name,sample(name))
@pytest.mark.parametrize('field',['secret','secretRef','credentialId','tenantId','permissions','Authorization','headers','proxy','redirects','method'])
def test_public_endpoint_never_carries_secrets_or_network_instructions(field):
 with pytest.raises(ValidationError):validate('source-endpoint',{**sample('source-endpoint'),field:'forged'})
@pytest.mark.parametrize('patch',[{'id':'../path'},{'id':'UPPER'},{'id':'x'*65},{'connectorKind':'ARBITRARY_HTTP'},{'name':' space'},{'name':'line\nbreak'},{'digest':'latest'},{'address':'https://fixture.invalid/api_jsonrpc.php'},{'address':'https://user:fixture@192.0.2.1/api_jsonrpc.php'},{'address':'https://192.0.2.1/api_jsonrpc.php?token=fixture'},{'address':'https://192.0.2.1/api_jsonrpc.php#fragment'},{'address':'https://192.0.2.1/other'}])
def test_metadata_shape_and_address_form_are_bounded(patch):
 with pytest.raises(ValidationError):validate('source-endpoint',{**sample('source-endpoint'),**patch})
@pytest.mark.parametrize('key',['address','tenantId','credentialPin','version','permissions'])
def test_pin_cannot_choose_an_address_or_change_identity(key):
 with pytest.raises(ValidationError):validate('source-endpoint-pin',{**sample('source-endpoint-pin'),key:'forged'})
def test_pin_requires_exact_digest():
 p=sample('source-endpoint-pin');del p['digest']
 with pytest.raises(ValidationError):validate('source-endpoint-pin',p)
def test_registry_scope_and_capacity_are_explicit():
 for tenants in [[],['*'],['fixture']*2,['fixture-'+str(n) for n in range(33)]]:
  p=sample('source-endpoint-registry');p['endpoints'][0]['tenants']=tenants
  with pytest.raises(ValidationError):validate('source-endpoint-registry',p)
 for name,key in [('source-endpoint-page','items'),('source-endpoint-registry','endpoints')]:
  p=sample(name);row=p[key][0];p[key]=[{**row,'id':'fixture-'+str(n)} for n in range(33)]
  with pytest.raises(ValidationError):validate(name,p)
  p[key]=[];validate(name,p)
def test_deployment_registry_cannot_register_secret_or_unknown_connector():
 for key,value in [('secret','fixture-secret'),('connectorKind','GENERIC_HTTP'),('digest','forged')]:
  p=sample('source-endpoint-registry');p['endpoints'][0][key]=value
  with pytest.raises(ValidationError):validate('source-endpoint-registry',p)
