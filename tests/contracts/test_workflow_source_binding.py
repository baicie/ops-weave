import json
from pathlib import Path
import pytest
from jsonschema import Draft202012Validator, ValidationError
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[2] / 'contracts'
SCHEMAS = [json.loads(p.read_text()) for p in (ROOT / 'schemas').rglob('*.json')]
REGISTRY = Registry().with_resources((s['$id'], Resource.from_contents(s)) for s in SCHEMAS if '$id' in s)
def validate(value):
    schema=json.loads((ROOT/'schemas/v2/workflow-definition.schema.json').read_text())
    Draft202012Validator(schema,registry=REGISTRY).validate(value)
def definition():
    d=json.loads((ROOT/'examples/v2/workflow-definition.json').read_text())
    pin=json.loads((ROOT/'examples/v2/workflow-source-configuration-pin.json').read_text())
    d['source']={'kind':'ZABBIX_HOST','instanceId':'fixture-host','configuration':pin}
    return d
def test_fixed_configuration_and_legacy_examples():
    validate(definition())
    validate(json.loads((ROOT/'examples/v2/workflow-definition.json').read_text()))
@pytest.mark.parametrize('field',['tenantId','userId','permissions','credentialPin','endpoint','secret','address','latest','configurationRevision'])
def test_pin_has_no_authority_or_mutable_alias(field):
    d=definition();d['source']['configuration'][field]='forged'
    with pytest.raises(ValidationError):validate(d)
@pytest.mark.parametrize('patch',[{'sourceId':'latest'},{'sourceId':'22222222-2222-4222-8222-22222222222Z'},{'revision':0},{'revision':101},{'revision':True},{'digest':'latest'},{'digest':'sha256:'+'A'*64}])
def test_invalid_pin(patch):
    d=definition();d['source']['configuration'].update(patch)
    with pytest.raises(ValidationError):validate(d)
def test_manual_source_and_null_pin_rejected():
    d=definition();d['source'].update(kind='MANUAL_SAMPLE',instanceId='manual')
    with pytest.raises(ValidationError):validate(d)
    d=definition();d['source']['configuration']=None
    with pytest.raises(ValidationError):validate(d)
