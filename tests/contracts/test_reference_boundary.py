"""The private-policy guard must fail without disclosing policy values."""
import importlib.util
import json
from pathlib import Path
import subprocess

import pytest

spec = importlib.util.spec_from_file_location('reference_boundary', Path(__file__).resolve().parents[2] / 'scripts/check_reference_boundary.py')
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


@pytest.fixture
def checkout(tmp_path):
    root = tmp_path / 'checkout'
    root.mkdir()
    subprocess.run(['git', 'init', '--quiet', str(root)], check=True)
    return root


@pytest.mark.parametrize('value', ['External Example', 'EXTERNAL EXAMPLE', 'Ｅｘｔｅｒｎａｌ Ｅｘａｍｐｌｅ'])
def test_content_guard_normalizes_private_terms(checkout, value):
    (checkout / 'page.md').write_text(value, encoding='utf-8')
    count, failures = guard.check(checkout, ['external example'])
    assert count == 1 and len(failures) == 1
    assert value not in failures[0]


def test_clean_candidates_pass_and_forced_ignored_candidates_are_checked(checkout):
    (checkout / '.gitignore').write_text('private/\n', encoding='utf-8')
    private = checkout / 'private'
    private.mkdir()
    (private / 'study.md').write_text('External Example', encoding='utf-8')
    assert not guard.check(checkout, ['external example'])[1]
    subprocess.run(['git', 'add', '--force', 'private/study.md'], cwd=checkout, check=True)
    assert len(guard.check(checkout, ['external example'])[1]) == 1


def test_restricted_path_does_not_disclose_the_name(checkout):
    (checkout / 'External Example.md').write_text('clean', encoding='utf-8')
    failures = guard.check(checkout, ['external example'])[1]
    assert failures and 'External Example' not in failures[0]


@pytest.mark.parametrize('value', [[], {}, {'version': 1, 'terms': []}, {'version': 1, 'terms': [None]}])
def test_invalid_private_policy_is_rejected(checkout, value):
    policy = checkout.parent / 'private-policy.json'
    policy.write_text(json.dumps(value), encoding='utf-8')
    with pytest.raises(ValueError):
        guard.load_terms(policy, checkout)


def test_private_policy_cannot_be_kept_inside_checkout(checkout):
    policy = checkout / 'policy.json'
    policy.write_text(json.dumps({'version': 1, 'terms': ['external example']}), encoding='utf-8')
    with pytest.raises(ValueError):
        guard.load_terms(policy, checkout)


def test_oversized_candidate_fails_instead_of_being_skipped(checkout):
    with (checkout / 'large.txt').open('wb') as stream:
        stream.truncate(33 * 1024 * 1024)
    assert guard.check(checkout, ['external example'])[1] == ['Candidate #1: exceeds the scan size limit']
