import json
import pytest
from jsonschema import ValidationError
from test_entity_page import ROOT, validate

def sample(name):
    return json.loads((ROOT / f'contracts/examples/{name}.json').read_text())

@pytest.mark.parametrize('key', ['accessToken', 'refreshToken', 'idToken', 'authorization', 'cookie'])
def test_session_never_returns_provider_or_cookie_credentials(key):
    value = sample('browser-session'); value[key] = 'secret'
    with pytest.raises(ValidationError): validate('browser-session', value)

@pytest.mark.parametrize('key,value', [('mode', 'dev'), ('dataMode', 'mock'), ('csrfToken', 'x'), ('csrfToken', 'a' * 32 + '\n'), ('loginPath', 'https://evil.invalid'), ('sessionId', None), ('expiresAt', None), ('expiresAt', 'yesterday'), ('principal', None), ('authenticated', False)])
def test_session_bounds(key, value):
    document = sample('browser-session'); document[key] = value
    with pytest.raises(ValidationError): validate('browser-session', document)

def test_anonymous_session_has_no_identity():
    value = sample('browser-session'); value.update(authenticated=False, sessionId=None, expiresAt=None, principal=None)
    validate('browser-session', value)

@pytest.mark.parametrize('key,value', [('externalSubject', ''), ('externalSubject', '\n'), ('externalSubject', 'a' * 256), ('tenantId', '../x'), ('revision', 0), ('permissions', ['shell']), ('permissions', ['entity.read', 'entity.read']), ('issuer', 'http://remote.invalid'), ('enabled', 'true'), ('scope', {'tenantWide': False, 'resources': []}), ('scope', {'tenantWide': True, 'resources': [{'type': 'entity', 'id': '*'}]})])
def test_operator_grant_bounds(key, value):
    document = sample('identity-grants'); document['grants'][0][key] = value
    with pytest.raises(ValidationError): validate('identity-grants', document)

def test_identity_contracts_reject_extra_fields():
    for name in ['identity-grants', 'browser-session', 'browser-logout']:
        value = sample(name); value['untrusted'] = True
        with pytest.raises(ValidationError): validate(name, value)
