import copy
import pytest
from jsonschema import ValidationError
from test_tool_gateway import sample, validate

@pytest.mark.parametrize('name', ['insight-submission', 'ai-insight', 'ai-insight-result', 'current-diagnose-request', 'current-context'])
def test_insight_examples(name):
    validate(name, sample(name))

@pytest.mark.parametrize('field,value', [('tenantId','other'), ('permissions',['ai.diagnose']), ('asOf','2020-01-01T00:00:00Z'), ('knowledgeMode','historical'), ('question',''), ('metric','arbitrary')])
def test_current_request_cannot_replace_identity_or_historical_cutoff(field, value):
    body = sample('current-diagnose-request'); body[field] = value
    with pytest.raises(ValidationError): validate('current-diagnose-request', body)

@pytest.mark.parametrize('field,value', [('tenantId','other'), ('subjectId','other'), ('permissions',['ai.diagnose']), ('approvalStatus','approved'), ('evidenceIds',[]), ('asOf','2026-02-30T00:00:00Z'), ('question','')])
def test_submission_rejects_identity_and_invalid_metadata(field, value):
    body = sample('insight-submission'); body[field] = value
    with pytest.raises(ValidationError): validate('insight-submission', body)

@pytest.mark.parametrize('path,value', [
    (['skill','version'],'1.0.0'), (['skill','digest'],'latest'), (['model','provider'],'fake'),
    (['insight','actions'],['restart']), (['insight','findings',0,'kind'],'confirmed_cause'),
    (['insight','findings',0,'evidenceRefs'],[]),
])
def test_submission_rejects_unversioned_or_executable_output(path, value):
    body = sample('insight-submission'); target = body
    for part in path[:-1]: target = target[part]
    target[path[-1]] = value
    with pytest.raises(ValidationError): validate('insight-submission', body)
