"""Closed registry wire examples; this suite never runs a model or activates a Skill."""
import copy
import hashlib
import json
import struct
from pathlib import Path

import pytest
import yaml
from jsonschema import Draft202012Validator, ValidationError

from test_workflow import validate

ROOT = Path(__file__).resolve().parents[2]
KINDS = (
    'content', 'save-command', 'review-command', 'publish-command', 'rollback-command',
    'draft', 'version', 'release-pointer', 'save-receipt', 'review-receipt',
    'publish-receipt', 'rollback-receipt', 'check', 'draft-page', 'version-page', 'conflict',
)


def example(kind):
    return json.loads((ROOT / 'contracts/examples/v2' / f'skill-{kind}.json').read_text(encoding='utf-8'))


def check(kind, value):
    validate('skill-' + kind, value)


def rejected(kind, value):
    with pytest.raises(ValidationError):
        check(kind, value)


@pytest.mark.parametrize('kind', KINDS)
def test_synthetic_registry_examples_have_closed_valid_schemas(kind):
    body = json.loads((ROOT / 'contracts/schemas/v2' / f'skill-{kind}.schema.json').read_text(encoding='utf-8'))
    Draft202012Validator.check_schema(body)
    check(kind, example(kind))


@pytest.mark.parametrize('kind', KINDS)
def test_no_public_registry_record_accepts_tenant_override(kind):
    value = example(kind)
    value['tenantId'] = 'forged'
    rejected(kind, value)


@pytest.mark.parametrize('kind', ['save-command', 'review-command', 'publish-command', 'rollback-command'])
@pytest.mark.parametrize('field', ['actor', 'permissions', 'approvalStatus', 'author', 'reviewer', 'draftId'])
def test_commands_cannot_grant_authority_or_override_path(kind, field):
    value = example(kind)
    value[field] = 'forged'
    rejected(kind, value)


@pytest.mark.parametrize('patch', [
    {'skillId': '../escape'}, {'skillId': 'custom/diagnosis'}, {'skillId': 'Custom.diagnosis'},
    {'skillId': 'a' * 65}, {'skillId': 'a.b.c.d.e'}, {'version': 'latest'},
    {'version': '1.0.0-beta'}, {'version': '10000.0.0'}, {'editVersion': 0},
    {'editVersion': 1000001}, {'editVersion': True}, {'editVersion': 1.5},
    {'requestId': 'not-a-uuid'}, {'content': None},
])
def test_save_coordinate_and_edit_bounds(patch):
    value = example('save-command')
    value.update(patch)
    rejected('save-command', value)


@pytest.mark.parametrize('patch', [
    {'executionTemplate': 'shell'}, {'prompt': ' '}, {'prompt': 'a' * 16385},
    {'outputSchema': ''}, {'outputSchema': 'a' * 32769}, {'allowedTools': []},
    {'allowedTools': ['incident.get@2.0.0'] * 2}, {'allowedTools': ['incident.get@latest']},
    {'knowledgeScope': 'all'}, {'modelPolicyRef': 'unrestricted'}, {'sideEffects': 'allowed'},
    {'maxToolCalls': 0}, {'maxToolCalls': 9}, {'maxEvidence': 0}, {'maxEvidence': 33},
    {'maxContextBytes': 1023}, {'maxContextBytes': 65537}, {'deadlineMs': 999},
    {'deadlineMs': 60001}, {'deadlineMs': False}, {'tests': []},
    {'tests': [example('content')['tests'][0]] * 21}, {'path': '../skill'}, {'url': 'untrusted'},
])
def test_content_shape_never_grants_execution_or_unbounded_budgets(patch):
    value = example('content')
    value.update(patch)
    rejected('content', value)


@pytest.mark.parametrize('patch', [
    {'id': 'Case'}, {'id': '../escape'}, {'id': 'a' * 33}, {'question': ' '},
    {'question': 'a' * 2001}, {'expectedOutput': ''}, {'expectedOutput': 'a' * 8193},
    {'tenantId': 'forged'}, {'expectedOutput': {'summary': 'must be inline JSON text'}},
])
def test_case_is_bounded_inline_text(patch):
    value = example('content')
    value['tests'][0].update(patch)
    rejected('content', value)


def test_draft_shape_and_template_check_are_deliberately_distinct():
    # Shape admits an authoring draft; service-side SkillCheck rejects this tool and template budget.
    value = example('content')
    value.update(allowedTools=['other.read@1.0.0'], maxToolCalls=8, maxEvidence=32, deadlineMs=60000)
    check('content', value)
    # Parsing/semantic checks are also server-side. Wire validity is not publication approval.
    value['outputSchema'] = 'not JSON yet'
    check('content', value)


@pytest.mark.parametrize('kind,field', [
    ('draft', 'createdAt'), ('draft', 'updatedAt'), ('version', 'publishedAt'),
    ('release-pointer', 'updatedAt'), ('save-receipt', 'savedAt'),
    ('review-receipt', 'submittedAt'), ('publish-receipt', 'publishedAt'),
    ('rollback-receipt', 'rolledBackAt'),
])
def test_registry_times_are_real_instants(kind, field):
    value = example(kind)
    value[field] = '2026-99-99'
    rejected(kind, value)


@pytest.mark.parametrize('kind', ['draft', 'version', 'save-receipt', 'publish-receipt', 'check'])
@pytest.mark.parametrize('digest', ['latest', 'sha256:' + 'a' * 63, 'sha256:' + 'A' * 64])
def test_content_pin_has_exact_digest_shape(kind, digest):
    value = example(kind)
    value['contentDigest'] = digest
    rejected(kind, value)


def test_original_receipt_states_are_not_rewritten_to_current_draft_state():
    value = example('save-receipt')
    value['state'] = 'PUBLISHED'
    rejected('save-receipt', value)
    value = example('review-receipt')
    value['state'] = 'DRAFT'
    rejected('review-receipt', value)
    value = example('version')
    value['review']['reviewer'] = 'forged'
    rejected('version', value)


@pytest.mark.parametrize('patch', [
    {'modelComparison': 'passed'}, {'modelComparison': None}, {'result': 'QUALITY_APPROVED'},
    {'cases': []}, {'result': 'FAILED'}, {'cases': [{'id': 'x', 'result': 'PASSED', 'detail': 'SECRET'}]},
    {'cases': [{'id': 'x', 'result': 'PASSED', 'detail': 'SCHEMA_INVALID'}]},
    {'cases': [{'id': 'x', 'result': 'FAILED', 'detail': 'OK'}]},
    {'modelOutput': 'private'}, {'reasoning': 'private'},
])
def test_check_cannot_fabricate_model_evaluation_or_contradict_cases(patch):
    value = example('check')
    value.update(patch)
    rejected('check', value)


def test_failed_shape_check_keeps_model_comparison_not_run():
    value = example('check')
    value.update(result='FAILED', cases=[{'id': 'schema', 'result': 'FAILED', 'detail': 'SCHEMA_INVALID'}])
    check('check', value)
    assert value['modelComparison'] == 'not-run'


@pytest.mark.parametrize('kind,cursor', [
    ('draft-page', '10500000-0000-4000-8000-000000000001'), ('version-page', '1.0.0'),
])
def test_page_bounds_and_has_more_cursor_pairing(kind, cursor):
    value = example(kind)
    value.update(hasMore=True, nextCursor=cursor)
    check(kind, value)
    value['nextCursor'] = None
    rejected(kind, value)
    value.update(hasMore=False, nextCursor=cursor)
    rejected(kind, value)
    value.update(hasMore=True, nextCursor=cursor, items=[])
    rejected(kind, value)
    value.update(hasMore=False, nextCursor=None, items=[])
    check(kind, value)
    value['items'] = [example(kind)['items'][0]] * 51
    rejected(kind, value)
    value['items'].pop()
    check(kind, value)


@pytest.mark.parametrize('patch', [{'toVersion': 'latest'}, {'reason': ' '}, {'reason': 'a' * 257}])
def test_rollback_requires_explicit_fixed_target_and_bounded_reason(patch):
    value = example('rollback-command')
    value.update(patch)
    rejected('rollback-command', value)


def content_digest(content):
    parts = ['opsweave-skill-content-v1', content['executionTemplate'], content['prompt'],
             content['outputSchema'], ','.join(content['allowedTools']), content['knowledgeScope'],
             content['modelPolicyRef'], content['sideEffects']]
    parts.extend(str(content[key]) for key in ['maxToolCalls', 'maxEvidence', 'maxContextBytes', 'deadlineMs'])
    for case in content['tests']:
        parts.extend(case[key] for key in ['id', 'question', 'expectedOutput'])
    digest = hashlib.sha256()
    for part in parts:
        data = part.encode('utf-8')
        digest.update(struct.pack('>Q', len(data)))
        digest.update(data)
    return 'sha256:' + digest.hexdigest()


def test_synthetic_example_digest_anchors_exact_content_order_and_bytes():
    value = example('content')
    expected = content_digest(value)
    assert expected == 'sha256:811ad7ccbf994e284835e5cabd96636a135b2b81e4fe611e0b7e3f61a54e3731'
    for kind in ['draft', 'version', 'save-receipt', 'publish-receipt', 'check']:
        assert example(kind)['contentDigest'] == expected
    changed = copy.deepcopy(value)
    changed['allowedTools'].reverse()
    assert content_digest(changed) != expected
    changed = copy.deepcopy(value)
    changed['outputSchema'] += ' '
    assert content_digest(changed) != expected
    # A valid digest shape cannot prove equality; the Java service/store must recompute it.
    changed_version = example('version')
    changed_version['content']['prompt'] += ' changed'
    check('version', changed_version)
    assert content_digest(changed_version['content']) != changed_version['contentDigest']


def test_openapi_covers_actual_nine_operations_and_closed_errors():
    paths = yaml.safe_load((ROOT / 'contracts/openapi/platform-draft.yaml').read_text(encoding='utf-8'))['paths']
    operations = {
        ('/api/v1/skills/drafts', 'post'): ('saveSkillDraft', 'save-command', 'save-receipt'),
        ('/api/v1/skills/drafts', 'get'): ('listSkillDrafts', None, 'draft-page'),
        ('/api/v1/skills/{draftId}/check', 'get'): ('checkSkillDraft', None, 'check'),
        ('/api/v1/skills/drafts/{draftId}/reviews', 'post'): ('submitSkillDraftReview', 'review-command', 'review-receipt'),
        ('/api/v1/skills/drafts/{draftId}/publications', 'post'): ('publishSkillDraft', 'publish-command', 'publish-receipt'),
        ('/api/v1/skills/{skillId}/rollbacks', 'post'): ('rollbackSkillRelease', 'rollback-command', 'rollback-receipt'),
        ('/api/v1/skills/{skillId}/versions/{version}', 'get'): ('getSkillVersion', None, 'version'),
        ('/api/v1/skills/{skillId}/versions', 'get'): ('listSkillVersions', None, 'version-page'),
        ('/api/v1/skills/{skillId}/release', 'get'): ('getSkillReleasePointer', None, 'release-pointer'),
    }
    for (path, method), (operation_id, command, response) in operations.items():
        operation = paths[path][method]
        assert operation['operationId'] == operation_id
        assert operation['security'] == [{'bearerAuth': []}, {'browserSession': []}]
        actual = operation['responses']['200']['content']['application/json']['schema']['$ref']
        assert actual == f'../schemas/v2/skill-{response}.schema.json'
        if command:
            actual = operation['requestBody']['content']['application/json']['schema']['$ref']
            assert actual == f'../schemas/v2/skill-{command}.schema.json'
            assert operation['responses']['409']['content']['application/json']['schema']['$ref'].endswith('skill-conflict.schema.json')
    assert '/api/v1/skills/drafts/{draftId}' not in paths
    assert 'modelComparison is always not-run' in paths['/api/v1/skills/{draftId}/check']['get']['description']


@pytest.mark.parametrize('code', ['SKILL_REQUEST_REUSED', 'SKILL_STATE_CONFLICT', 'SKILL_VERSION_IMMUTABLE', 'SKILL_COORDINATE_RESERVED', 'SKILL_CHECK_FAILED'])
def test_registry_conflicts_are_closed_content_free_codes(code):
    check('conflict', {'error': code})
    rejected('conflict', {'error': code, 'prompt': 'private'})
    rejected('conflict', {'error': 'arbitrary submitted content'})
