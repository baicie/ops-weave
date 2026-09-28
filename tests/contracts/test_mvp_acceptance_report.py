"""A probe report must never manufacture milestone or real-environment approval."""
import copy
import json
import os
from pathlib import Path
import subprocess

import pytest
from jsonschema import Draft202012Validator, FormatChecker, ValidationError

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / 'contracts/schemas/v2/mvp-acceptance-report.schema.json').read_text(encoding='utf-8'))
EXAMPLE = json.loads((ROOT / 'contracts/examples/v2/mvp-acceptance-report.json').read_text(encoding='utf-8'))
VALIDATOR = Draft202012Validator(SCHEMA, format_checker=FormatChecker())


def test_rehearsal_example_is_valid():
    VALIDATOR.validate(EXAMPLE)


@pytest.mark.parametrize('key,value', [
    ('mode', 'real'), ('milestonesSatisfied', True), ('unverified', []),
    ('errorCode', 'SOURCE_FAILED\nprivate text'), ('platformUrl', 'https://user:secret@example.invalid'),
    ('prompt', 'private question'), ('responseBody', 'private response'),
    ('startedAt', 'yesterday'), ('completedAt', None),
])
def test_report_cannot_claim_approval_or_include_unbounded_text(key, value):
    report = copy.deepcopy(EXAMPLE)
    report[key] = value
    with pytest.raises(ValidationError):
        VALIDATOR.validate(report)


@pytest.mark.parametrize('field', list(EXAMPLE))
def test_every_report_field_is_required(field):
    report = copy.deepcopy(EXAMPLE)
    del report[field]
    with pytest.raises(ValidationError):
        VALIDATOR.validate(report)


def test_pass_requires_all_seven_ordered_probes():
    for count in range(7):
        report = copy.deepcopy(EXAMPLE)
        report['steps'] = report['steps'][:count]
        with pytest.raises(ValidationError):
            VALIDATOR.validate(report)
    report = copy.deepcopy(EXAMPLE)
    report['steps'][0], report['steps'][1] = report['steps'][1], report['steps'][0]
    with pytest.raises(ValidationError):
        VALIDATOR.validate(report)


def test_fixture_and_mock_cannot_become_real_candidates_by_changing_only_mode():
    report = copy.deepcopy(EXAMPLE)
    report['mode'] = 'real-candidate'
    with pytest.raises(ValidationError):
        VALIDATOR.validate(report)
    report.update(sourceDataMode='zabbix-jsonrpc', modelProvider='rig-openai')
    with pytest.raises(ValidationError):
        VALIDATOR.validate(report)
    for step in report['steps'][:3]:
        step['evidence']['dataMode'] = 'zabbix-jsonrpc'
    report['steps'][0]['evidence']['reportedVersion'] = '7.0.0'
    report['steps'][6]['evidence']['modelProvider'] = 'rig-openai'
    VALIDATOR.validate(report)


@pytest.mark.parametrize('mode', ['unverified', 'rehearsal'])
def test_configuration_failure_has_a_valid_report_without_claiming_any_steps(mode):
    report = copy.deepcopy(EXAMPLE)
    report.update(mode=mode, status='failed', steps=[], sourceDataMode=None, modelProvider=None,
                  failedStep='CONFIG', errorCode='PLATFORM_URL_REQUIRED')
    VALIDATOR.validate(report)
    report['mode'] = 'real-candidate'
    with pytest.raises(ValidationError):
        VALIDATOR.validate(report)


def test_step_evidence_does_not_accept_raw_data():
    for key in ['name', 'summary', 'modelOutput', 'Authorization', 'body']:
        report = copy.deepcopy(EXAMPLE)
        report['steps'][3]['evidence'][key] = 'private text'
        with pytest.raises(ValidationError):
            VALIDATOR.validate(report)


def test_actual_cli_configuration_failure_matches_the_published_schema(tmp_path):
    target = tmp_path / 'failure.json'
    env = {key: value for key, value in os.environ.items() if not key.startswith('OPSWEAVE_ACCEPTANCE_')}
    result = subprocess.run(['node', str(ROOT / 'scripts/acceptance/real-acceptance.mjs'), f'--report={target}'],
                            env=env, capture_output=True, text=True, timeout=20)
    assert result.returncode == 1
    report = json.loads(target.read_text(encoding='utf-8'))
    VALIDATOR.validate(report)
    assert report['mode'] == 'unverified'
    assert report['steps'] == []


def test_actual_protocol_fixture_reports_match_the_published_schema(tmp_path):
    target = tmp_path / 'protocol-fixture-reports.json'
    env = {**os.environ, 'OPSWEAVE_TEST_ACCEPTANCE_REPORTS': str(target)}
    result = subprocess.run(['node', '--test', str(ROOT / 'tests/acceptance/real-acceptance.test.mjs')],
                            env=env, capture_output=True, text=True, encoding='utf-8', timeout=30)
    assert result.returncode == 0, result.stdout + result.stderr
    reports = json.loads(target.read_text(encoding='utf-8'))
    assert len(reports) >= 30
    for report in reports:
        VALIDATOR.validate(report)
    assert {'rehearsal', 'unverified', 'real-candidate'} == {report['mode'] for report in reports}
