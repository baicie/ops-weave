import copy
import json
from pathlib import Path
import pytest
from jsonschema import Draft202012Validator, FormatChecker, ValidationError
from referencing import Registry, Resource
ROOT=Path(__file__).resolve().parents[2]
REGISTRY=Registry().with_resources((p.as_uri(),Resource.from_contents(json.loads(p.read_text(encoding='utf-8')))) for p in (ROOT/'contracts/schemas').rglob('*.schema.json'))
def validate(name,value):
 Draft202012Validator({'$ref':(ROOT/'contracts/schemas/v1'/f'{name}.schema.json').as_uri()},registry=REGISTRY,format_checker=FormatChecker()).validate(value)
def example():return json.loads((ROOT/'contracts/examples/source-center-page.json').read_text(encoding='utf-8'))
def test_fixture_source_center():
 p=example();validate('source-center-page',p);validate('source-setup',p['setups']['items'][0])
 c=p['types'][0]['connection'];c.update(dataMode='zabbix-jsonrpc',endpoint='http://127.0.0.1:18088/api_jsonrpc.php',credentialRef='env:OPSWEAVE_ZABBIX_TOKEN');validate('source-center-page',p)
@pytest.mark.parametrize('patch',[{'dataMode':'real'},{'dataMode':'zabbix-jsonrpc'},{'endpoint':'http://user:secret@host/api'},{'credentialRef':'actual-secret'},{'instanceId':'../x'}])
def test_connection_claims_are_closed(patch):
 p=example();p['types'][0]['connection'].update(patch)
 with pytest.raises(ValidationError):validate('source-center-page',p)
@pytest.mark.parametrize('patch',[{'tenantId':'forged'},{'source':{'kind':'HTTP','instanceId':'remote'}},{'name':' '},{'digest':'bad'},{'workflowId':'wrong'},{'description':'x'*501}])
def test_setup_bounds(patch):
 s=example()['setups']['items'][0];s.update(patch)
 with pytest.raises(ValidationError):validate('source-setup',s)
def test_command_cannot_supply_endpoint_credentials_or_identity():
 s=example()['setups']['items'][0];c=dict(requestId=s['id'],name=s['name'],description=s['description'],source=s['source'],connectionDigest=s['connectionDigest'],target=s['initialTarget']);validate('source-setup-command',c)
 for field in ['tenantId','userId','url','credential','workflowId','execute']:
  with pytest.raises(ValidationError):validate('source-setup-command',{**c,field:'forged'})
def test_duplicate_type_and_legacy_canvas_claim_rejected():
 p=example();p['types'][2]=copy.deepcopy(p['types'][0])
 with pytest.raises(ValidationError):validate('source-center-page',p)
 p=example();p['types'][2]['status']='AVAILABLE'
 with pytest.raises(ValidationError):validate('source-center-page',p)
