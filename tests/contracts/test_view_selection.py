import json
import pytest
from jsonschema import ValidationError
from test_entity_page import ROOT, validate

NAMES = ['inventory-view-selection', 'incident-view-selection', 'metrics-view-selection']

def sample(name):
    return json.loads((ROOT / f'contracts/examples/{name}.json').read_text())

@pytest.mark.parametrize('name', NAMES)
@pytest.mark.parametrize('field', ['tenantId', 'token', 'permissions', 'action', 'result'])
def test_selection_cannot_carry_identity_credentials_commands_or_cached_results(name, field):
    value = sample(name); value[field] = 'untrusted'
    with pytest.raises(ValidationError): validate(name, value)

@pytest.mark.parametrize('name,field,value', [
    ('inventory-view-selection', 'q', 'x' * 101), ('inventory-view-selection', 'q', 'bad\nquery'),
    ('inventory-view-selection', 'q', 'bad\u0085query'), ('inventory-view-selection', 'type', 'shell'),
    ('inventory-view-selection', 'lifecycle', '*'), ('inventory-view-selection', 'after', ''),
    ('incident-view-selection', 'status', 'DELETED'), ('incident-view-selection', 'incidentId', '../any'),
    ('metrics-view-selection', 'metricKey', 'bad metric'), ('metrics-view-selection', 'range', '24h'),
    ('metrics-view-selection', 'from', -1), ('metrics-view-selection', 'till', 10000000000),
    ('metrics-view-selection', 'from', 1.5), ('metrics-view-selection', 'entityId', None),
    ('metrics-view-selection', 'from', None), ('metrics-view-selection', 'till', None),
    ('inventory-view-selection', 'q', 'bad\n'), ('incident-view-selection', 'incidentId', '00000000-0000-4000-8000-000000000001\n'),
    ('metrics-view-selection', 'metricKey', 'metric\n'),
])
def test_selection_bounds(name, field, value):
    item = sample(name); item[field] = value
    with pytest.raises(ValidationError): validate(name, item)

def test_latest_window_can_be_unselected_but_must_omit_both_bounds():
    item = sample('metrics-view-selection'); item.update({'entityId': None, 'metricKey': '', 'from': None, 'till': None})
    validate('metrics-view-selection', item)
