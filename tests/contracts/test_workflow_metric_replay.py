from pathlib import Path
import copy,json
import pytest
from jsonschema import ValidationError
from test_workflow import validate
E=Path(__file__).resolve().parents[2]/'contracts/examples/v2'
def example(name):return json.loads((E/('workflow-metric-replay-'+name+'.json')).read_text())
@pytest.mark.parametrize('name',['command','execute','plan','receipt','page','execution'])
def test_closed_bounded_replay_examples(name):validate('workflow-metric-replay-'+name,example(name))
@pytest.mark.parametrize('patch',[{'tenantId':'forged'},{'subjectId':'forged'},{'permissions':['workflow.replay']},{'notifications':True},{'actions':True},{'samples':[{'value':'raw'}]},{'requestId':'1-1-1-1-1'},{'from':'2026-10-05T00:00:00+00:00'},{'revision':False}])
def test_no_identity_budget_or_raw_input_in_command(patch):
 v=example('command');v.update(patch)
 with pytest.raises(ValidationError):validate('workflow-metric-replay-command',v)
@pytest.mark.parametrize('patch',[{'notifications':True},{'actions':True},{'purpose':'SEND_NOTIFICATIONS'},{'outputPolicy':'OVERWRITE_STREAM'},{'proof':None},{'state':'CONFIRMED'},{'error':'OUTPUT_UNCONFIRMED'},{'body':'raw customer value'}])
def test_plan_never_claims_execution_or_side_effects(patch):
 v=example('plan');v.update(patch)
 with pytest.raises(ValidationError):validate('workflow-metric-replay-plan',v)
@pytest.mark.parametrize('patch',[{'inputCount':61},{'inputCount':True},{'filtered':-1},{'values':['raw']},{'inputDigest':'bad'},{'timestamps':[1]*61}])
def test_bounded_proof_has_no_values(patch):
 v=example('plan');v['proof'].update(patch)
 with pytest.raises(ValidationError):validate('workflow-metric-replay-plan',v)
@pytest.mark.parametrize('state,error',[('UNKNOWN',None),('UNKNOWN','OUTPUT_REJECTED'),('FAILED','OUTPUT_UNCONFIRMED'),('CONFIRMED','OUTPUT_UNCONFIRMED'),('PENDING','FORBIDDEN')])
def test_output_uncertainty_is_not_a_rejection(state,error):
 v=example('receipt');v.update(state=state,error=error)
 with pytest.raises(ValidationError):validate('workflow-metric-replay-receipt',v)
def test_prepare_and_empty_metadata_are_distinct_from_confirmed_output():
 v=example('plan');v.update(state='PREPARING',proof=None,error=None);validate('workflow-metric-replay-plan',v)
 v.update(state='FAILED',error='SOURCE_UNAVAILABLE');validate('workflow-metric-replay-plan',v)
def test_replay_series_cannot_masquerade_as_continuous_series():
 v=example('plan');v['proof']['labels']['collection_mode']='WINDOW_60S'
 with pytest.raises(ValidationError):validate('workflow-metric-replay-plan',v)
def test_page_is_metadata_only_and_bounded():
 v=example('page');v['items']=[copy.deepcopy(v['items'][0])]*21
 with pytest.raises(ValidationError):validate('workflow-metric-replay-page',v)
