import json
from pathlib import Path
import pytest
from jsonschema import Draft202012Validator, FormatChecker, ValidationError
ROOT=Path(__file__).resolve().parents[2]
@pytest.mark.parametrize('name',['evidence','context-pack'])
def test_v2(name):
    schema=json.loads((ROOT/f'contracts/schemas/v2/{name}.schema.json').read_text())
    value=json.loads((ROOT/f'contracts/examples/v2/{name}.json').read_text())
    Draft202012Validator(schema,format_checker=FormatChecker()).validate(value)
@pytest.mark.parametrize('field',['tenantId','permissions','approvalStatus','provider','endpoint'])
def test_request_cannot_override_trusted_context(field):
    schema=json.loads((ROOT/'contracts/schemas/v1/diagnose-request.schema.json').read_text())
    value=json.loads((ROOT/'contracts/examples/diagnose-request.json').read_text());value[field]='attacker'
    with pytest.raises(ValidationError): Draft202012Validator(schema).validate(value)
def test_runtime_skill_schema_matches_contract():
    a=json.loads((ROOT/'contracts/schemas/v1/insight-draft.schema.json').read_text())
    b=json.loads((ROOT/'extensions/skills/incident-diagnosis/output.schema.json').read_text())
    assert a==b
@pytest.mark.parametrize('field',['execute','confidence','tenantId'])
def test_model_output_cannot_add_privileged_fields(field):
    schema=json.loads((ROOT/'contracts/schemas/v1/insight-draft.schema.json').read_text())
    value=json.loads((ROOT/'contracts/examples/insight-draft.json').read_text());value[field]='injected'
    with pytest.raises(ValidationError): Draft202012Validator(schema).validate(value)
def test_agent_is_rust_not_python_service():
    assert (ROOT/'apps/agent-runtime/Cargo.toml').is_file()
    assert not list((ROOT/'apps/agent-runtime').rglob('*.py'))

def test_zabbix_host_pipeline_has_required_nodes():
    pipeline = json.loads((ROOT/'contracts/examples/pipeline-definition.json').read_text())
    types = [node['type'] for node in pipeline['nodes']]
    assert types == ['Source', 'Parse', 'Map', 'Validate', 'EntityResolve', 'WriteObservation']
    assert pipeline['source'] == {'type': 'zabbix', 'objectType': 'host'}
