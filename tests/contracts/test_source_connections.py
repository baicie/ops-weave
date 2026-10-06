import copy
import pytest
from jsonschema import ValidationError
from test_source_endpoints import sample, validate

@pytest.mark.parametrize('name',['source-connection-configuration','source-connection-create','source-connection-edit','source-connection-read','source-connection-history','source-connection-receipt'])
def test_connection_fixture_contract(name): validate(name,sample(name))

def test_scoped_configuration_fixture_contract():
 validate('source-connection-configuration',sample('source-connection-configuration-scoped'))

@pytest.mark.parametrize('name',['source-connection-create','source-connection-edit'])
@pytest.mark.parametrize('field',['address','secret','secretRef','Authorization','headers','proxy','tenantId','userId','permissions','schedule','workflowId'])
def test_write_only_accepts_registered_pins(name,field):
 with pytest.raises(ValidationError): validate(name,{**sample(name),field:'forged'})

@pytest.mark.parametrize('pin',['endpointPin','credentialPin'])
@pytest.mark.parametrize('field',['address','secret','tenantId','permissions','latest'])
def test_nested_pin_is_closed(pin,field):
 p=sample('source-connection-create');p[pin][field]='forged'
 with pytest.raises(ValidationError):validate('source-connection-create',p)

@pytest.mark.parametrize('groups',[[],['0'],['01'],['7','7'],['1']*33,['9'*20]])
def test_connection_scope_is_nonempty_unique_and_bounded(groups):
 p=sample('source-connection-create');p['hostGroupIds']=groups
 with pytest.raises(ValidationError):validate('source-connection-create',p)

@pytest.mark.parametrize('patch',[{'requestId':'latest'},{'name':''},{'name':' spaced'},{'name':'line\nbreak'},{'description':'x'*501},{'expectedEditVersion':0},{'expectedEditVersion':1001}])
def test_edit_is_bounded(patch):
 with pytest.raises(ValidationError):validate('source-connection-edit',{**sample('source-connection-edit'),**patch})

def test_immutable_config_and_receipt_are_closed():
 for name,path in [('source-connection-configuration',[]),('source-connection-receipt',['receipt']),('source-connection-read',['connection'])]:
  p=sample(name);target=p
  for key in path:target=target[key]
  target['secret']='fixture'
  with pytest.raises(ValidationError):validate(name,p)
 p=sample('source-connection-configuration');p['connectorVersion']='latest'
 with pytest.raises(ValidationError):validate('source-connection-configuration',p)
 p=sample('source-connection-configuration-scoped');p.pop('hostGroupIds')
 with pytest.raises(ValidationError):validate('source-connection-configuration',p)
 p=sample('source-connection-history');p['items']*=101
 with pytest.raises(ValidationError):validate('source-connection-history',p)

def test_read_legacy_and_unavailable_are_explicit():
 p=sample('source-connection-read');p['connection']=None;p['availability']='LEGACY';validate('source-connection-read',p)
 p['availability']='CONNECTED'
 with pytest.raises(ValidationError):validate('source-connection-read',p)
