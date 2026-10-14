import copy
import json
from pathlib import Path
import re

import pytest
from jsonschema import Draft202012Validator, FormatChecker, ValidationError
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[2]
OPENAPI = ROOT / 'contracts/openapi/platform-draft.yaml'


def sample(name):
    return json.loads((ROOT / 'contracts/examples/v2' / f'{name}.json').read_text(encoding='utf-8'))


def validator(name):
    registry = Registry()
    for path in (ROOT / 'contracts/schemas').rglob('*.json'):
        schema = json.loads(path.read_text(encoding='utf-8'))
        if '$id' in schema:
            registry = registry.with_resource(schema['$id'], Resource.from_contents(schema))
    return Draft202012Validator(json.loads((ROOT / 'contracts/schemas/v2' / f'{name}.schema.json').read_text(encoding='utf-8')),
                                registry=registry, format_checker=FormatChecker())


def rejects(name, value):
    with pytest.raises(ValidationError):
        validator(name).validate(value)


def _section(path):
    text = OPENAPI.read_text(encoding='utf-8')
    marker = f'  {path}:\n'
    start = text.index(marker)
    next_path = re.search(r'\n  /[^\n]+:\n', text[start + len(marker):])
    end = start + len(marker) + next_path.start() if next_path else len(text)
    return text[start:end]


def _method(path, method):
    section = _section(path)
    match = re.search(rf'\n    {method}:\n(?P<body>.*?)(?=\n    (?:get|post|patch|put|delete|options|head|trace):|\Z)', section, re.DOTALL)
    assert match, f'missing OpenAPI operation: {method.upper()} {path}'
    return match.group('body')


@pytest.mark.parametrize('name', ['ai-run-command', 'ai-run', 'ai-run-page', 'ai-run-reconciliation',
                                  'ai-run-cancel-command', 'ai-run-cancel-receipt',
                                  'ai-run-retry-command', 'ai-run-retry-receipt',
                                  'ai-run-event', 'ai-run-event-page'])
def test_explicit_fixtures(name):
    validator(name).validate(sample(name))


def test_command_is_closed_and_cannot_replace_identity():
    body = sample('ai-run-command')
    for field in ['requestId', 'incidentId', 'question', 'timeRange', 'knowledgeMode', 'skill']:
        missing = dict(body)
        del missing[field]
        rejects('ai-run-command', missing)
    for key, value in [('tenantId', 'other'), ('owner', 'someone'), ('actor', 'someone'),
                       ('permissions', ['ai.diagnose']), ('approvalStatus', 'approved'),
                       ('status', 'RUNNING'), ('runId', '10000000-0000-4000-8000-000000000083'),
                       ('cancelRequestedAt', '2026-10-13T01:00:00Z'), ('dryRun', True),
                       ('requestId', 'not-a-uuid'), ('incidentId', 'not-a-uuid'),
                       ('question', ''), ('question', '   '), ('question', 'x' * 2001),
                       ('knowledgeMode', 'historical'), ('knowledgeMode', 'CURRENT')]:
        broken = dict(body)
        broken[key] = value
        rejects('ai-run-command', broken)


def test_command_pins_one_published_skill_and_a_closed_window():
    body = sample('ai-run-command')
    for path, value in [(['skill', 'id'], 'incident.diagnose@latest'), (['skill', 'version'], '2.0.1'),
                        (['skill', 'digest'], 'latest'), (['skill', 'digest'], 'sha256:' + 'a' * 63),
                        (['skill', 'tenantId'], 'other')]:
        broken = copy.deepcopy(body)
        target = broken
        for part in path[:-1]:
            target = target[part]
        target[path[-1]] = value
        rejects('ai-run-command', broken)
    for window in [{'from': 'yesterday', 'to': '2026-10-13T01:30:00Z'},
                   {'from': '2026-10-13T01:00:00Z'},
                   {'from': '2026-10-13T01:00:00Z', 'to': '2026-10-13T01:30:00Z', 'asOf': '2026-10-13T01:30:00Z'}]:
        broken = dict(body, timeRange=window)
        rejects('ai-run-command', broken)


def test_run_record_is_closed_and_states_stay_in_the_machine():
    value = sample('ai-run')
    rejects('ai-run', dict(value, schemaVersion='1.0'))
    rejects('ai-run', dict(value, tenantId='other'))
    rejects('ai-run', dict(value, owner='someone'))
    rejects('ai-run', dict(value, permissions=['ai.diagnose']))
    rejects('ai-run', dict(value, evidenceIds=[]))
    rejects('ai-run', dict(value, statusVersion=-1))
    rejects('ai-run', dict(value, statusVersion=1.5))
    rejects('ai-run', dict(value, statusVersion=1000001))
    rejects('ai-run', dict(value, commandDigest='sha256:zz'))
    rejects('ai-run', dict(value, runId='not-a-uuid'))
    rejects('ai-run', {k: v for k, v in value.items() if k != 'attempt'})
    rejects('ai-run', dict(value, attempt=-1))
    rejects('ai-run', dict(value, attempt=1.5))
    rejects('ai-run', dict(value, attempt=1000001))
    for status in ['QUEUED', 'COMPLETED', 'WAITING_APPROVAL', 'running', 'CANCELLED ', 'UNKNOWN']:
        rejects('ai-run', dict(value, status=status))
    for status in ['PENDING', 'RUNNING', 'WAITING_TOOL', 'SUCCEEDED']:
        validator('ai-run').validate(dict(value, status=status))
    for status, reason in [('FAILED', 'RESULT_LOST'), ('CANCELLED', 'CANCELLED_BY_REQUEST')]:
        validator('ai-run').validate(dict(value, status=status, reason=reason))


def test_landing_reason_and_execution_metadata_are_closed():
    value = sample('ai-run')
    recovered = dict(value, status='FAILED', reason='RESULT_LOST', attempt=2,
                     disclosure={'adoptedPriorResult': False, 'rerun': False, 'priorModelCalls': 1,
                                 'uncertainPriorFees': 1, 'duplicateFeeRisk': True})
    validator('ai-run').validate(recovered)
    validator('ai-run').validate(dict(value, status='CANCELLED', reason='CANCELLED_BY_REQUEST',
                                      cancelRequestedAt='2026-10-13T01:00:02Z'))
    # A landing reason exists only on FAILED/CANCELLED, never beside a complete SUCCEEDED run.
    rejects('ai-run', dict(value, reason='EXECUTION_FAILED'))
    rejects('ai-run', dict(value, status='SUCCEEDED', reason='CANCELLED_BY_REQUEST'))
    rejects('ai-run', dict(value, status='FAILED'))
    rejects('ai-run', dict(value, status='CANCELLED'))
    rejects('ai-run', dict(value, status='RUNNING', reason='DEADLINE'))
    for reason in ['CANCEL_REQUESTED', 'cancelled_by_request', 'PROVIDER_DOWN', '']:
        rejects('ai-run', dict(value, status='FAILED', reason=reason))
    rejects('ai-run', dict(value, cancelRequestedAt='not-a-time'))
    rejects('ai-run', dict(value, cancelRequestedAt=None))
    rejects('ai-run', dict(value, disclosure=[]))
    rejects('ai-run', dict(value, disclosure={'adoptedPriorResult': False, 'rerun': True,
                                              'priorModelCalls': 1, 'uncertainPriorFees': 0,
                                              'duplicateFeeRisk': False}))
    rejects('ai-run', dict(recovered, disclosure=dict(recovered['disclosure'], extra=True)))
    rejects('ai-run', dict(recovered, disclosure=dict(recovered['disclosure'], uncertainPriorFees=2)))


def test_control_commands_are_closed_and_carry_no_identity():
    for name in ['ai-run-cancel-command', 'ai-run-retry-command']:
        body = sample(name)
        rejects(name, {})
        rejects(name, dict(body, runId='10000000-0000-4000-8000-000000000083'))
        for key, value in [('tenantId', 'other'), ('owner', 'someone'), ('actor', 'someone'),
                           ('permissions', ['ai.diagnose']), ('approvalStatus', 'approved'),
                           ('status', 'RUNNING'), ('reason', 'user asked'), ('dryRun', True),
                           ('requestId', 'not-a-uuid')]:
            rejects(name, dict(body, **{key: value}))


def test_control_receipts_are_closed_and_the_disclosure_stays_explicit():
    cancel = sample('ai-run-cancel-receipt')
    validator('ai-run-cancel-receipt').validate(dict(cancel, recorded=False, status='CANCELLED'))
    for key, value in [('recorded', 'true'), ('status', 'QUEUED'), ('requestedAt', 'yesterday'),
                       ('tenantId', 'other'), ('reconciliation', {})]:
        rejects('ai-run-cancel-receipt', dict(cancel, **{key: value}))
    rejects('ai-run-cancel-receipt', {k: v for k, v in cancel.items() if k != 'recorded'})
    retry = sample('ai-run-retry-receipt')
    validator('ai-run-retry-receipt').validate(dict(retry, attempt=1, status='FAILED',
        reconciliation={'adoptedPriorResult': False, 'rerun': False, 'priorModelCalls': 1,
                        'uncertainPriorFees': 1, 'duplicateFeeRisk': True}))
    for key, value in [('attempt', 0), ('attempt', 1.5), ('status', 'UNKNOWN'),
                       ('reconciliation', {'adoptedPriorResult': True, 'rerun': True, 'priorModelCalls': 0,
                                           'uncertainPriorFees': 0, 'duplicateFeeRisk': False}),
                       ('reconciliation', {'adoptedPriorResult': False, 'rerun': False, 'priorModelCalls': 0,
                                           'uncertainPriorFees': 0, 'duplicateFeeRisk': True}),
                       ('reconciliation', {'adoptedPriorResult': False, 'rerun': True, 'priorModelCalls': 1,
                                           'uncertainPriorFees': 0, 'duplicateFeeRisk': False})]:
        rejects('ai-run-retry-receipt', dict(retry, **{key: value}))
    rejects('ai-run-retry-receipt', {k: v for k, v in retry.items() if k != 'reconciliation'})


def test_page_is_bounded_and_consistent():
    value = sample('ai-run-page')
    validator('ai-run-page').validate(value)
    empty = dict(value, items=[], nextCursor=None, hasMore=False)
    validator('ai-run-page').validate(empty)
    rejects('ai-run-page', dict(value, truncated=True))
    rejects('ai-run-page', dict(value, items=None))
    rejects('ai-run-page', {k: v for k, v in value.items() if k != 'hasMore'})
    rejects('ai-run-page', dict(value, hasMore=True, nextCursor=None))
    rejects('ai-run-page', dict(value, hasMore=True, nextCursor='10000000-0000-4000-8000-000000000083', items=[]))
    rejects('ai-run-page', dict(value, hasMore=False, nextCursor='10000000-0000-4000-8000-000000000083'))
    rejects('ai-run-page', dict(value, items=[copy.deepcopy(value['items'][0]) for _ in range(51)]))
    rejects('ai-run-page', dict(value, items=[value['items'][0], copy.deepcopy(value['items'][0])]))


@pytest.mark.parametrize('cursor', ['', 'FORBIDDEN-UPPER', 'not-a-uuid', '10000000-0000-4000-8000-00000000008'])
def test_page_cursor_is_a_closed_run_reference(cursor):
    value = sample('ai-run-page')
    rejects('ai-run-page', dict(value, hasMore=True, nextCursor=cursor))
    validator('ai-run-page').validate(dict(value, hasMore=True, nextCursor='10000000-0000-4000-8000-000000000083'))


def test_run_page_items_are_closed_runs():
    value = sample('ai-run-page')
    item = copy.deepcopy(value['items'][0])
    item['trace'] = {}
    rejects('ai-run-page', dict(value, items=[item]))
    item = copy.deepcopy(value['items'][0])
    item['status'] = 'QUEUED'
    rejects('ai-run-page', dict(value, items=[item]))
    item = copy.deepcopy(value['items'][0])
    item['status'] = 'SUCCEEDED'
    validator('ai-run-page').validate(dict(value, items=[item]))


def test_event_record_is_closed_and_carries_no_identity_or_reasoning():
    value = sample('ai-run-event')
    rejects('ai-run-event', dict(value, schemaVersion='2.0'))
    rejects('ai-run-event', dict(value, tenantId='other'))
    rejects('ai-run-event', dict(value, owner='someone'))
    rejects('ai-run-event', dict(value, payloadVersion=2))
    rejects('ai-run-event', dict(value, payloadVersion='1'))
    rejects('ai-run-event', {k: v for k, v in value.items() if k != 'payloadVersion'})
    rejects('ai-run-event', dict(value, seq=0))
    rejects('ai-run-event', dict(value, seq=1.5))
    rejects('ai-run-event', dict(value, seq=1000000000001))
    rejects('ai-run-event', dict(value, runId='not-a-uuid'))
    rejects('ai-run-event', dict(value, occurredAt='yesterday'))
    rejects('ai-run-event', dict(value, type='RUN'))
    for hidden in [('reasoning', 'the model thought ...'), ('summary', 'hidden reasoning'),
                   ('token', 'secret'), ('rawLog', 'customer log line'), ('prompt', 'system prompt')]:
        rejects('ai-run-event', dict(value, **{hidden[0]: hidden[1]}))
        rejects('ai-run-event', dict(value, payload=dict(value['payload'], **{hidden[0]: hidden[1]})))


def test_event_payload_stays_closed_per_type():
    disclosure = {'adoptedPriorResult': False, 'rerun': False, 'priorModelCalls': 1,
                  'uncertainPriorFees': 1, 'duplicateFeeRisk': True}

    def event(kind, payload):
        return {'runId': '10000000-0000-4000-8000-000000000083', 'seq': 1, 'type': kind,
                'occurredAt': '2026-10-13T01:00:01Z', 'payloadVersion': 1, 'payload': payload}

    validator('ai-run-event').validate(event('SUBMITTED', {'status': 'PENDING', 'attempt': 0}))
    validator('ai-run-event').validate(event('CLAIMED', {'status': 'RUNNING', 'attempt': 3}))
    validator('ai-run-event').validate(event('TOOL_WAIT', {'status': 'WAITING_TOOL'}))
    validator('ai-run-event').validate(event('STATUS_CHANGED', {'status': 'RUNNING'}))
    validator('ai-run-event').validate(event('STATUS_CHANGED', {'status': 'WAITING_TOOL'}))
    validator('ai-run-event').validate(event('CANCEL_REQUESTED', {}))
    validator('ai-run-event').validate(event('RECONCILED', {'disclosure': disclosure}))
    validator('ai-run-event').validate(event('FINISHED', {'status': 'SUCCEEDED'}))
    validator('ai-run-event').validate(event('FINISHED', {'status': 'SUCCEEDED', 'disclosure': disclosure}))
    validator('ai-run-event').validate(event('FINISHED', {'status': 'FAILED', 'reason': 'RESULT_LOST',
                                                          'disclosure': disclosure}))
    validator('ai-run-event').validate(event('FINISHED', {'status': 'CANCELLED', 'reason': 'CANCELLED_BY_REQUEST'}))
    # The type and the payload close each other: no extra field, no missing field, no mismatch.
    for kind, payload in [
        ('SUBMITTED', {'status': 'PENDING'}), ('SUBMITTED', {'status': 'PENDING', 'attempt': 1}),
        ('SUBMITTED', {'status': 'RUNNING', 'attempt': 0}), ('SUBMITTED', {'attempt': 0}),
        ('SUBMITTED', {'status': 'PENDING', 'attempt': 0, 'reason': 'DEADLINE'}),
        ('SUBMITTED', {'status': 'PENDING', 'attempt': 0, 'disclosure': disclosure}),
        ('CLAIMED', {'status': 'RUNNING'}), ('CLAIMED', {'status': 'RUNNING', 'attempt': 0}),
        ('CLAIMED', {'status': 'WAITING_TOOL', 'attempt': 1}), ('CLAIMED', {'status': 'RUNNING', 'attempt': 1.5}),
        ('TOOL_WAIT', {}), ('TOOL_WAIT', {'status': 'RUNNING'}), ('TOOL_WAIT', {'status': 'WAITING_TOOL', 'attempt': 1}),
        ('STATUS_CHANGED', {}), ('STATUS_CHANGED', {'status': 'PENDING'}),
        ('STATUS_CHANGED', {'status': 'SUCCEEDED'}), ('STATUS_CHANGED', {'status': 'RUNNING', 'attempt': 1}),
        ('CANCEL_REQUESTED', {'status': 'PENDING'}), ('CANCEL_REQUESTED', {'attempt': 1}),
        ('CANCEL_REQUESTED', {'reason': 'CANCELLED_BY_REQUEST'}), ('CANCEL_REQUESTED', {'disclosure': disclosure}),
        ('RECONCILED', {}), ('RECONCILED', {'status': 'RUNNING', 'disclosure': disclosure}),
        ('RECONCILED', {'reason': 'RESULT_LOST', 'disclosure': disclosure}),
        ('RECONCILED', {'disclosure': dict(disclosure, extra=True)}),
        ('RECONCILED', {'disclosure': dict(disclosure, uncertainPriorFees=2)}),
        ('FINISHED', {'status': 'RUNNING'}), ('FINISHED', {'status': 'PENDING'}),
        ('FINISHED', {'status': 'SUCCEEDED', 'reason': 'DEADLINE'}),
        ('FINISHED', {'status': 'FAILED'}), ('FINISHED', {'status': 'CANCELLED'}),
        ('FINISHED', {'status': 'FAILED', 'reason': 'CANCELLED_BY_REQUEST', 'attempt': 1}),
        ('FINISHED', {'status': 'FAILED', 'reason': 'RESULT_LOST', 'disclosure': dict(disclosure, rerun=True)}),
    ]:
        rejects('ai-run-event', event(kind, payload))


def test_event_page_is_bounded_and_marks_expired_cursors():
    value = sample('ai-run-event-page')
    rejects('ai-run-event-page', dict(value, truncated=True))
    rejects('ai-run-event-page', dict(value, gap='yes'))
    rejects('ai-run-event-page', dict(value, gap=1))
    rejects('ai-run-event-page', {k: v for k, v in value.items() if k != 'gap'})
    rejects('ai-run-event-page', dict(value, runId='not-a-uuid'))
    rejects('ai-run-event-page', dict(value, hasMore=False, nextCursor='2'))
    rejects('ai-run-event-page', dict(value, hasMore=True, nextCursor=None))
    rejects('ai-run-event-page', dict(value, hasMore=True, items=[]))
    # An expired cursor answers with the retained window plus the current snapshot.
    validator('ai-run-event-page').validate(dict(value, items=[], nextCursor=None, hasMore=False, gap=True))
    validator('ai-run-event-page').validate(dict(value, gap=True))
    for cursor in ['', '0', '02', '-1', 'FORBIDDEN-UPPER', 'x' * 19]:
        rejects('ai-run-event-page', dict(value, hasMore=True, nextCursor=cursor))
    snapshot = value['snapshot']
    for broken in [{k: v for k, v in snapshot.items() if k != 'status'},
                   dict(snapshot, status='QUEUED'), dict(snapshot, statusVersion=-1),
                   dict(snapshot, statusVersion=1.5), dict(snapshot, attempt=-1),
                   dict(snapshot, updatedAt='yesterday'), dict(snapshot, extra=True)]:
        rejects('ai-run-event-page', dict(value, snapshot=broken))
    rejects('ai-run-event-page', dict(value, snapshot=None))
    items = value['items']
    rejects('ai-run-event-page', dict(value, items=[items[0], dict(items[0])]))
    broken = copy.deepcopy(items[0])
    broken['payload']['status'] = 'RUNNING'
    rejects('ai-run-event-page', dict(value, items=[broken, items[1]]))
    broken = copy.deepcopy(items[0])
    broken['command'] = 'run the diagnosis again'
    rejects('ai-run-event-page', dict(value, items=[broken, items[1]]))
    rejects('ai-run-event-page', dict(value, items=items + [copy.deepcopy(items[1]) for _ in range(50)]))


def test_openapi_registers_the_run_routes_with_closed_schemas():
    submit = _method('/api/v1/ai/runs', 'post')
    assert 'operationId: submitAiRun' in submit
    assert '../schemas/v2/ai-run-command.schema.json' in submit
    assert '../schemas/v2/ai-run.schema.json' in submit
    listing = _method('/api/v1/ai/runs', 'get')
    assert 'operationId: listAiRuns' in listing
    assert '../schemas/v2/ai-run-page.schema.json' in listing
    read = _method('/api/v1/ai/runs/{runId}', 'get')
    assert 'operationId: readAiRun' in read
    assert '../schemas/v2/ai-run.schema.json' in read
    cancel = _method('/api/v1/ai/runs/{runId}/cancel', 'post')
    assert 'operationId: cancelAiRun' in cancel
    assert '../schemas/v2/ai-run-cancel-command.schema.json' in cancel
    assert '../schemas/v2/ai-run-cancel-receipt.schema.json' in cancel
    retry = _method('/api/v1/ai/runs/{runId}/retries', 'post')
    assert 'operationId: retryAiRun' in retry
    assert '../schemas/v2/ai-run-retry-command.schema.json' in retry
    assert '../schemas/v2/ai-run-retry-receipt.schema.json' in retry
    events = _method('/api/v1/ai/runs/{runId}/events', 'get')
    assert 'operationId: listAiRunEvents' in events
    assert '../schemas/v2/ai-run-event-page.schema.json' in events
    assert 'afterSeq' in events
    stream = _method('/api/v1/ai/runs/{runId}/events/stream', 'get')
    assert 'operationId: streamAiRunEvents' in stream
    assert 'text/event-stream' in stream
    assert 'Last-Event-ID' in stream
    for name in ['ai-run-command.schema.json', 'ai-run.schema.json', 'ai-run-page.schema.json',
                 'ai-run-reconciliation.schema.json', 'ai-run-cancel-command.schema.json',
                 'ai-run-cancel-receipt.schema.json', 'ai-run-retry-command.schema.json',
                 'ai-run-retry-receipt.schema.json', 'ai-run-event.schema.json',
                 'ai-run-event-page.schema.json']:
        schema = (ROOT / 'contracts/schemas/v2' / name).read_text(encoding='utf-8')
        assert '"additionalProperties": false' in schema


def test_openapi_error_codes_are_a_closed_set():
    expected = {
        ('/api/v1/ai/runs', 'post'): {'200', '400', '401', '403', '404', '409', '503'},
        ('/api/v1/ai/runs', 'get'): {'200', '400', '401', '403', '404', '503'},
        ('/api/v1/ai/runs/{runId}', 'get'): {'200', '400', '401', '403', '404', '503'},
        ('/api/v1/ai/runs/{runId}/cancel', 'post'): {'200', '400', '401', '403', '404', '409', '503'},
        ('/api/v1/ai/runs/{runId}/retries', 'post'): {'200', '400', '401', '403', '404', '409', '503'},
        ('/api/v1/ai/runs/{runId}/events', 'get'): {'200', '400', '401', '403', '404', '503'},
        ('/api/v1/ai/runs/{runId}/events/stream', 'get'): {'200', '400', '401', '403', '404', '503'},
    }
    for (path, method), codes in expected.items():
        body = _method(path, method)
        found = set(re.findall(r"^        '(\d{3})':", body, re.MULTILINE))
        assert found == codes, f'{method.upper()} {path}: {sorted(found)}'


def test_operation_ids_stay_unique():
    text = OPENAPI.read_text(encoding='utf-8')
    operation_ids = re.findall(r'^      operationId: ([^\s]+)$', text, re.MULTILINE)
    assert len(operation_ids) == len(set(operation_ids))
    assert operation_ids.count('submitAiRun') == 1
    assert operation_ids.count('cancelAiRun') == 1
    assert operation_ids.count('retryAiRun') == 1
    assert operation_ids.count('listAiRunEvents') == 1
    assert operation_ids.count('streamAiRunEvents') == 1
