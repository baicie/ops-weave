import copy,json
from pathlib import Path
import pytest
from jsonschema import Draft202012Validator,FormatChecker,ValidationError
from referencing import Registry,Resource
ROOT=Path(__file__).resolve().parents[2]
REGISTRY=Registry().with_resources((p.as_uri(),Resource.from_contents(json.loads(p.read_text(encoding='utf-8')))) for p in (ROOT/'contracts/schemas').rglob('*.schema.json'))
OPENAPI=ROOT/'contracts/openapi/platform-draft.yaml'
def sample(n):return json.loads((ROOT/'contracts/examples/v2'/f'{n}.json').read_text(encoding='utf-8'))
def validate(n,v):Draft202012Validator({'$ref':(ROOT/'contracts/schemas/v2'/f'{n}.schema.json').as_uri()},registry=REGISTRY,format_checker=FormatChecker()).validate(v)

def test_openapi_declares_instance_inspection_and_discovery_routes():
    """The generated API inventory must expose the implemented v2 inspection boundary."""
    import yaml
    paths=yaml.safe_load(OPENAPI.read_text(encoding='utf-8'))['paths']
    expected={
        '/api/v2/data-sources/{id}/test': ('post','source-inspection-command.schema.json','source-inspection-read.schema.json'),
        '/api/v2/data-sources/{id}/connection-check': ('post','source-inspection-command.schema.json','source-inspection-read.schema.json'),
        '/api/v2/data-sources/{id}/discover': ('post','source-inspection-command.schema.json','source-inspection-read.schema.json'),
        '/api/v2/data-sources/{id}/discover-metrics': ('post','source-inspection-command.schema.json','source-metric-discovery-read.schema.json'),
        '/api/v2/data-sources/{id}/metric-discoveries': ('post','source-metric-page-command.schema.json','source-metric-page-read.schema.json'),
        '/api/v2/data-sources/{id}/inspections': ('get',None,'source-inspection-page.schema.json'),
        '/api/v2/data-sources/{id}/inspections/{requestId}': ('get',None,'source-inspection-read.schema.json'),
        '/api/v2/data-sources/{id}/connection-checks': ('get',None,'source-inspection-page.schema.json'),
    }
    for path,(method,request_schema,response_schema) in expected.items():
        assert path in paths
        operation=paths[path][method]
        assert operation['security']==[{'bearerAuth':[]},{'browserSession':[]}]
        if request_schema:
            body=operation['requestBody']['content']['application/json']['schema']['$ref']
            assert body.endswith('/'+request_schema)
        response=operation['responses']['200']['content']['application/json']['schema']['$ref']
        assert response.endswith('/'+response_schema)
@pytest.mark.parametrize('n',['source-inspection-command','source-inspection-read','source-inspection-page'])
def test_explicit_fixture(n):validate(n,sample(n))
@pytest.mark.parametrize('field',['tenantId','userId','url','secretRef','credential','cursor','sample','kind','execute'])
def test_request_cannot_expand_trust_or_read_budget(field):
 with pytest.raises(ValidationError):validate('source-inspection-command',{**sample('source-inspection-command'),field:'forged'})
@pytest.mark.parametrize('patch',[{'configurationRevision':0},{'configurationRevision':101},{'connectionDigest':'bad'},{'requestId':'../x'}])
def test_command_limits(patch):
 with pytest.raises(ValidationError):validate('source-inspection-command',{**sample('source-inspection-command'),**patch})
@pytest.mark.parametrize('patch',[{'state':'PASSED'},{'dataMode':'MOCK'},{'availableAt':None},{'check':{'reachable':True,'statusCode':'READ_VERIFIED','reportedVersion':None}},{'configurationRevision':101},{'sourceId':'not-id'}])
def test_completed_result_closed(patch):
 r=sample('source-inspection-read')['view']['inspection'];r.update(patch)
 with pytest.raises(ValidationError):validate('source-inspection',r)
@pytest.mark.parametrize('patch',[{'observedRecords':6},{'scope':'ALL_HOSTS'},{'complete':True,'scanConsistency':'UNVERIFIED'},{'fields':[{'name':'Authorization','type':'TEXT','nullable':False}]},{'observedRecords':0},{'statusCode':'UNREACHABLE'}])
def test_discovery_does_not_claim_complete_or_return_raw_data(patch):
 r=sample('source-inspection-read')['view']['inspection'];r['discovery'].update(patch)
 with pytest.raises(ValidationError):validate('source-inspection',r)
def test_unknown_retains_original_pin_without_result():
 r=sample('source-inspection-read')['view']['inspection'];r.update(state='UNKNOWN',availableAt=None,expiresAt=None,check=None,discovery=None);validate('source-inspection',r)
 r['availableAt']='2026-10-03T00:00:01Z'
 with pytest.raises(ValidationError):validate('source-inspection',r)
def test_recent_metadata_is_bounded():
 p=sample('source-inspection-page');p['items']=p['items']*21
 with pytest.raises(ValidationError):validate('source-inspection-page',p)
