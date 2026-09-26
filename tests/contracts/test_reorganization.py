import pytest
from jsonschema import ValidationError
from test_tool_gateway import sample, validate

@pytest.mark.parametrize('name', ['incident-reorganization-request', 'incident-reorganization', 'incident-reorganization-result', 'incident-reorganization-page'])
def test_examples(name):
    validate(name, sample(name))

@pytest.mark.parametrize('field,value', [('tenantId', 'other'), ('actor', 'admin'), ('kind', 'AUTO_MERGE'), ('expectedSourceVersion', 0), ('expectedTargetVersion', 0), ('title', 'merge rename'), ('reason', ''), ('problemKeys', [{'sourceInstanceId':'zabbix-1','problemEventId':'1'}])])
def test_merge_rejects_identity_and_ambiguous_mutations(field, value):
    request = sample('incident-reorganization-request'); request[field] = value
    with pytest.raises(ValidationError): validate('incident-reorganization-request', request)

def test_split_requires_nonempty_subset_new_target_and_title():
    request = sample('incident-reorganization-request'); request.update(kind='SPLIT', expectedTargetVersion=0, title='New investigation', problemKeys=[{'sourceInstanceId':'zabbix-1','problemEventId':'30001'}])
    validate('incident-reorganization-request', request)
    for field,value in [('problemKeys',[]), ('title',None), ('expectedTargetVersion',1)]:
        invalid = dict(request); invalid[field] = value
        with pytest.raises(ValidationError): validate('incident-reorganization-request', invalid)

def test_incident_allows_versioned_organization_without_changing_legacy_snapshots():
    record = sample('incident-record'); validate('incident-record',record)
    record['organization'] = {'version':2,'changeId':'88888888-1111-4111-8111-111111111111','mergedInto':None}
    validate('incident-record',record)
    record['organization']['version'] = 9007199254740991
    validate('incident-record',record)
    record['organization']['version'] = 0
    with pytest.raises(ValidationError): validate('incident-record',record)
