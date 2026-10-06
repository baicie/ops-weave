import copy
import pytest
from jsonschema import ValidationError
from test_source_inspections import sample, validate

@pytest.mark.parametrize('name',['metric-mapping-pin','metric-mapping-binding','metric-mapping-definition','metric-mapping-view','metric-mapping-command','metric-mapping-receipt','metric-mapping-page','metric-mapping-read','metric-mapping-receipt-read'])
def test_explicit_synthetic_mapping_maintenance(name): validate(name,sample(name))

@pytest.mark.parametrize('field',['tenantId','ownerId','rules','url','sourceKey','execute','minimum','credentialPin'])
def test_command_cannot_submit_identity_rules_or_io(field):
    with pytest.raises(ValidationError):validate('metric-mapping-command',{**sample('metric-mapping-command'),field:'forged'})

@pytest.mark.parametrize('patch',[{'expectedBindingVersion':0},{'expectedBindingVersion':1000000000},{'requestId':'forged'},{'mappingPin':None}])
def test_command_limits(patch):
    with pytest.raises(ValidationError):validate('metric-mapping-command',{**sample('metric-mapping-command'),**patch})

@pytest.mark.parametrize('patch',[{'id':'../forged'},{'revision':0},{'revision':1000001},{'digest':'bad'},{'valueTransform':'forged'}])
def test_pin_closed(patch):
    with pytest.raises(ValidationError):validate('metric-mapping-pin',{**sample('metric-mapping-pin'),**patch})

def test_legacy_is_explicitly_unpinned_and_list_bounded():
    b=sample('metric-mapping-binding');b['mappingPin']=None;validate('metric-mapping-binding',b)
    del b['mappingPin']
    with pytest.raises(ValidationError):validate('metric-mapping-binding',b)
    page=sample('metric-mapping-page');page['items']*=21
    with pytest.raises(ValidationError):validate('metric-mapping-page',page)

@pytest.mark.parametrize('field',['mappingPin','dimensionSchema','fixedDimensions','minimum','maximum'])
def test_full_definition_required(field):
    definition=sample('metric-mapping-definition');del definition[field]
    with pytest.raises(ValidationError):validate('metric-mapping-definition',definition)
