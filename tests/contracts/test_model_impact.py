import copy
import json
from pathlib import Path
import pytest
from jsonschema import ValidationError
from test_model_catalog import validate

ROOT=Path(__file__).resolve().parents[2]
def example(name):return json.loads((ROOT/'contracts/examples'/f'{name}.json').read_text(encoding='utf-8'))
@pytest.mark.parametrize('name',['model-revision-review','model-references'])
def test_closed_examples(name):validate(name,example(name))
@pytest.mark.parametrize('name',['model-revision-review','model-references'])
@pytest.mark.parametrize('key',['tenantId','subject','authority','rawRecords','credentials'])
def test_private_or_authority_overrides_rejected(name,key):
 value=example(name);value['review' if name.endswith('review') else 'report'][key]='forged'
 with pytest.raises(ValidationError):validate(name,value)
@pytest.mark.parametrize('patch',[{'revision':0},{'revision':10001},{'id':'custom/../../model'},{'digest':'sha256:bad'}])
def test_fixed_target_bounds(patch):
 value=example('model-references');value['report']['target'].update(patch)
 with pytest.raises(ValidationError):validate('model-references',value)
@pytest.mark.parametrize('patch',[{'roles':['FROM']},{'roles':['OUTPUT','OUTPUT']},{'revision':1000001},{'fieldIds':['tenant_id.forged']},{'state':'DRAFT'},{'rawRows':[]},{'tasks':[{'kind':'SHELL','state':'RUNNING','generation':1}]}])
def test_workflow_reference_is_metadata_only(patch):
 value=example('model-references');value['report']['references']['items'][0].update(patch)
 with pytest.raises(ValidationError):validate('model-references',value)
def test_unavailable_workflows_never_look_like_authorized_zero():
 value=example('model-references');value['report']['workflowsAvailable']=False
 with pytest.raises(ValidationError):validate('model-references',value)
 value['report']['references']['items']=[];validate('model-references',value)
def test_reference_cap_is_explicit():
 value=example('model-references');value['report']['references']['items']*=51
 with pytest.raises(ValidationError):validate('model-references',value)
@pytest.mark.parametrize('patch',[{'compatible':False},{'reasons':['INCOMPATIBLE_CHANGE']},{'editVersion':0},{'reviewedAt':'invalid'},{'changes':[{'fieldId':'bad.field','property':'SQL','before':None,'after':'code','compatible':True}]}])
def test_review_bounds_and_decision(patch):
 value=example('model-revision-review');value['review'].update(patch)
 with pytest.raises(ValidationError):validate('model-revision-review',value)
def test_review_request_has_no_new_definition_or_identity():
 value={'ref':{'id':'custom.fixture','revision':2},'expectedEditVersion':1,'digest':'sha256:'+'a'*64};validate('model-revision-review-request',value)
 for key in ['definition','tenant','subject','permissions']:
  bad=copy.deepcopy(value);bad[key]={}
  with pytest.raises(ValidationError):validate('model-revision-review-request',bad)

def test_compatible_decision_cannot_hide_an_incompatible_change():
 value=example('model-revision-review');value['review']['changes'][0]['compatible']=False
 with pytest.raises(ValidationError):validate('model-revision-review',value)
