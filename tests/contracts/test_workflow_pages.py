import copy
import json
from pathlib import Path
import pytest
from jsonschema import Draft202012Validator, ValidationError, FormatChecker
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[2]


def sample(name):
    return json.loads((ROOT / 'contracts/examples/v2' / f'{name}.json').read_text(encoding='utf-8'))


def validator(name):
    registry = Registry()
    for path in (ROOT / 'contracts/schemas').rglob('*.json'):
        schema = json.loads(path.read_text(encoding='utf-8'))
        if '$id' in schema:
            registry = registry.with_resource(schema['$id'], Resource.from_contents(schema))
    return Draft202012Validator(json.loads((ROOT / 'contracts/schemas/v2' / f'{name}.schema.json').read_text(encoding='utf-8')), registry=registry, format_checker=FormatChecker())


@pytest.mark.parametrize('name', ['workflow-entry-page', 'workflow-run-page'])
def test_explicit_fixture_pages(name):
    validator(name).validate(sample(name))


def test_empty_final_page_is_a_complete_answer():
    for name in ['workflow-entry-page', 'workflow-run-page']:
        value = sample(name)
        value['items'] = []
        value['nextCursor'] = None
        value['hasMore'] = False
        validator(name).validate(value)


@pytest.mark.parametrize('name', ['workflow-entry-page', 'workflow-run-page'])
@pytest.mark.parametrize('mutation', ['extra', 'schema_version', 'missing_items', 'missing_cursor', 'missing_has_more', 'false_has_more_with_cursor', 'true_has_more_without_cursor', 'true_has_more_empty_items', 'too_many_items', 'duplicate_items'])
def test_pages_are_closed_and_consistent(name, mutation):
    value = sample(name)
    cursor = 'fixture-second-transform:1' if name == 'workflow-entry-page' else value['items'][0]['receipt']['id']
    if mutation == 'extra':
        value['truncated'] = True
    elif mutation == 'schema_version':
        value['schemaVersion'] = '1.0'
    elif mutation == 'missing_items':
        del value['items']
    elif mutation == 'missing_cursor':
        del value['nextCursor']
    elif mutation == 'missing_has_more':
        del value['hasMore']
    elif mutation == 'false_has_more_with_cursor':
        value['hasMore'] = False
        value['nextCursor'] = cursor
    elif mutation == 'true_has_more_without_cursor':
        value['hasMore'] = True
        value['nextCursor'] = None
    elif mutation == 'true_has_more_empty_items':
        value['hasMore'] = True
        value['nextCursor'] = cursor
        value['items'] = []
    elif mutation == 'too_many_items':
        value['items'] = [copy.deepcopy(value['items'][0]) for _ in range(51)]
    else:
        value['items'] = [copy.deepcopy(value['items'][0]), copy.deepcopy(value['items'][0])]
    with pytest.raises(ValidationError):
        validator(name).validate(value)


@pytest.mark.parametrize('cursor', ['', 'FORBIDDEN-UPPER', 'no-revision', 'flow:0', 'flow:10001', ':1', '1flow:1', 'flow:1:2', '10000000-0000-4000-8000-000000000073'])
def test_entry_page_cursor_is_a_closed_entry_reference(cursor):
    value = sample('workflow-entry-page')
    value['nextCursor'] = cursor
    with pytest.raises(ValidationError):
        validator('workflow-entry-page').validate(value)


@pytest.mark.parametrize('cursor', ['', 'flow:1', 'not-a-uuid', '10000000-0000-4000-8000-00000000007'])
def test_run_page_cursor_is_a_closed_run_uuid(cursor):
    value = sample('workflow-run-page')
    value['hasMore'] = True
    value['nextCursor'] = cursor
    with pytest.raises(ValidationError):
        validator('workflow-run-page').validate(value)


@pytest.mark.parametrize('mutation', ['tenant', 'owner', 'bad_state', 'draft_zero_edit', 'published_edit', 'extra_entry_field', 'oversized_layout'])
def test_entry_items_keep_the_immutable_entry_contract(mutation):
    value = sample('workflow-entry-page')
    item = value['items'][0]
    if mutation == 'tenant':
        item['tenantId'] = 'forged'
    elif mutation == 'owner':
        item['owner'] = 'forged'
    elif mutation == 'bad_state':
        item['state'] = 'RUNNING'
    elif mutation == 'draft_zero_edit':
        item['state'] = 'DRAFT'
    elif mutation == 'published_edit':
        item['editVersion'] = 1
    elif mutation == 'extra_entry_field':
        item['layout']['source'] = {'x': 1, 'y': 1, 'z': 2}
    else:
        item['layout']['source'] = {'x': 4001, 'y': 0}
    with pytest.raises(ValidationError):
        validator('workflow-entry-page').validate(value)


@pytest.mark.parametrize('mutation', ['tenant', 'bad_mode', 'bad_revision', 'extra_run_field', 'bad_receipt'])
def test_run_items_keep_the_run_summary_contract(mutation):
    value = sample('workflow-run-page')
    item = value['items'][0]
    if mutation == 'tenant':
        item['tenantId'] = 'forged'
    elif mutation == 'bad_mode':
        item['mode'] = 'EXECUTE'
    elif mutation == 'bad_revision':
        item['revision'] = 0
    elif mutation == 'extra_run_field':
        item['trace'] = {}
    else:
        item['receipt']['origin'] = 'arbitrary-upstream'
    with pytest.raises(ValidationError):
        validator('workflow-run-page').validate(value)


def test_run_page_cursor_follows_the_last_returned_receipt():
    value = sample('workflow-run-page')
    value['hasMore'] = True
    value['nextCursor'] = value['items'][0]['receipt']['id']
    validator('workflow-run-page').validate(value)
