import copy
import pytest
from jsonschema import ValidationError
from test_source_inspections import sample, validate

@pytest.mark.parametrize('name', ['source-metric-page', 'source-metric-manifest-reference', 'source-metric-page-command', 'source-metric-page-read', 'source-inspection-storage'])
def test_explicit_synthetic_page(name): validate(name, sample(name))

@pytest.mark.parametrize('patch', [{'offset': 1}, {'offset': 1000}, {'limit': 21}, {'manifest': None}, {'scanConsistency': 'UNVERIFIED'}, {'statusCode': 'UNREACHABLE'}, {'nextOffset': 20}, {'complete': False}, {'itemIds': ['1']}, {'cursor': 'forged'}])
def test_closed_page_and_terminal_status(patch):
    with pytest.raises(ValidationError): validate('source-metric-page', {**sample('source-metric-page'), **patch})

@pytest.mark.parametrize('key', ['offset', 'cursor', 'itemids', 'url', 'tenantId', 'credentialPin'])
def test_client_cannot_choose_membership(key):
    with pytest.raises(ValidationError): validate('source-metric-page-command', {**sample('source-metric-page-command'), key: 'forged'})

def test_private_manifest_is_not_public_wire():
    receipt = sample('source-inspection-storage')
    validate('source-inspection-storage', receipt)
    with pytest.raises(ValidationError): validate('source-inspection', receipt)
    del receipt['metricMembership']
    validate('source-inspection', receipt)
    with pytest.raises(ValidationError): validate('source-inspection-storage', receipt)

def test_failed_page_preserves_absence():
    value = sample('source-metric-page')
    value.update(manifest=None, items=[], statusCode='UNREACHABLE', scanConsistency='UNVERIFIED', complete=False, nextOffset=None)
    validate('source-metric-page', value)
    value['complete'] = True
    with pytest.raises(ValidationError): validate('source-metric-page', value)

def test_page_result_exclusive_and_old_receipt_compatible():
    value = sample('source-metric-page-read')['view']['inspection']
    for patch in [{'metricPage': None}, {'previousRequestId': 'forged'}, {'kind': 'TEST'}, {'state': 'UNKNOWN'}, {'metricDiscovery': sample('source-metric-discovery')}]:
        with pytest.raises(ValidationError): validate('source-inspection', {**value, **patch})
    validate('source-inspection-read', sample('source-inspection-read'))
    validate('source-metric-discovery-read', sample('source-metric-discovery-read'))

@pytest.mark.parametrize('patch', [{'total': 1001}, {'total': -1}, {'itemIds': ['1']}, {'snapshotId': 'forged'}])
def test_manifest_reference_is_bounded_and_opaque(patch):
    with pytest.raises(ValidationError): validate('source-metric-manifest-reference', {**sample('source-metric-manifest-reference'), **patch})
