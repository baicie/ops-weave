import copy,json
from pathlib import Path
import pytest
from jsonschema import Draft202012Validator,FormatChecker,ValidationError
from referencing import Registry,Resource
ROOT=Path(__file__).resolve().parents[2]
REGISTRY=Registry().with_resources((p.as_uri(),Resource.from_contents(json.loads(p.read_text(encoding='utf-8')))) for p in (ROOT/'contracts/schemas').rglob('*.schema.json'))
def sample(n):return json.loads((ROOT/'contracts/examples/v2'/f'{n}.json').read_text(encoding='utf-8'))
def validate(n,v):Draft202012Validator({'$ref':(ROOT/'contracts/schemas/v2'/f'{n}.schema.json').as_uri()},registry=REGISTRY,format_checker=FormatChecker()).validate(v)
@pytest.mark.parametrize('n',['source-credential','source-credential-pin','source-credential-create','source-credential-edit','source-credential-revoke','source-credential-version','source-credential-receipt','source-credential-read','source-credential-command-read','source-credential-page','source-credential-version-page'])
def test_explicit_fixture(n):validate(n,sample(n))
@pytest.mark.parametrize('field',['tenantId','owner','permissions','keyId','ciphertext','secretRef','endpoint','execute'])
def test_write_cannot_expand_identity_or_choose_key_material(field):
 with pytest.raises(ValidationError):validate('source-credential-create',{**sample('source-credential-create'),field:'forged'})
@pytest.mark.parametrize('secret',['',' ','line\nbreak','unicode-密钥','x'*4097,None,True])
def test_secret_is_bounded_header_safe_text(secret):
 with pytest.raises(ValidationError):validate('source-credential-create',{**sample('source-credential-create'),'secret':secret})
@pytest.mark.parametrize('field',['secret','ciphertext','keyId','nonce','secretDigest','Authorization'])
def test_public_metadata_and_receipts_cannot_return_protected_content(field):
 for n in ['source-credential','source-credential-receipt']:
  with pytest.raises(ValidationError):validate(n,{**sample(n),field:'forged'})
@pytest.mark.parametrize('patch',[{'revision':0},{'revision':101},{'editVersion':0},{'editVersion':1001},{'state':'RESTORED'},{'name':'  name'},{'name':'line\nfeed'},{'name':'a\x80b'},{'name':'\u00a0name'},{'name':'a\ufeffb'},{'versionId':'../file'}])
def test_metadata_limits(patch):
 with pytest.raises(ValidationError):validate('source-credential',{**sample('source-credential'),**patch})
def test_revoked_edit_cannot_rotate_or_restore_with_a_secret():
 e=sample('source-credential-edit');e.update(state='REVOKED',secret='fixture-value')
 with pytest.raises(ValidationError):validate('source-credential-edit',e)
 e['secret']=None;validate('source-credential-edit',e)
def test_reference_requires_an_exact_opaque_version():
 p=sample('source-credential-pin');del p['versionId']
 with pytest.raises(ValidationError):validate('source-credential-pin',p)
def test_read_and_history_capacities():
 for n,limit in [('source-credential-page',20),('source-credential-version-page',100)]:
  p=sample(n);p['items']=p['items']*(limit+1)
  with pytest.raises(ValidationError):validate(n,p)
def test_revoke_cannot_choose_another_subject_or_resolve_a_secret():
 for key in ['owner','secret','keyId','versionId','credentialId']:
  with pytest.raises(ValidationError):validate('source-credential-revoke',{**sample('source-credential-revoke'),key:'forged'})
