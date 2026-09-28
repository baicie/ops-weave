"""The M4 corpus is synthetic and never grants real-environment or human sign-off."""
import copy
import hashlib
import json
from datetime import datetime
from pathlib import Path

import pytest
from jsonschema import ValidationError
from test_pipeline import validate

ROOT = Path(__file__).resolve().parents[2]
CORPUS = json.loads((ROOT / 'contracts/evals/current-diagnosis.json').read_text(encoding='utf-8'))


def test_current_evaluation_inputs_follow_native_context_and_evidence_contracts():
    validate('current-diagnosis-eval', CORPUS)


def test_required_scenarios_are_unique_and_have_pending_human_annotations():
    cases = CORPUS['cases']
    assert len({case['id'] for case in cases}) == len(cases)
    assert {case['category'] for case in cases} == {
        'normal', 'missing', 'conflict', 'expired', 'unauthorized', 'injection', 'timeout', 'invalid-output'
    }
    for case in cases:
        assert case['annotation']['status'] == 'pending-human-review'
        assert case['expected']['minRechecks'] <= case['expected']['maxRechecks']
        for document in case['context']['evidence']:
            assert document['dataModes'] == ['labeled-fixture']
            assert document['evidence']['trust'] == 'untrusted_data'


def test_corpus_pins_the_existing_published_skill_without_rewriting_it():
    digest = hashlib.sha256()
    for name in ['skill.json', 'prompt.md', 'output.schema.json']:
        content = (ROOT / 'extensions/skills/incident-diagnosis-current' / name).read_bytes()
        digest.update(len(content).to_bytes(8, 'big'))
        digest.update(content)
    assert CORPUS['skillRef']['digest'] == 'sha256:' + digest.hexdigest()


def test_labels_preserve_all_source_warnings_and_actual_expiry_boundary():
    clock = datetime.fromisoformat(CORPUS['referenceClock'])
    for case in CORPUS['cases']:
        documents = case['context']['evidence']
        warnings = {warning for doc in documents for warning in doc['warnings']}
        assert warnings == set(case['context']['missingData']) == set(case['expected']['protectedMissing'])
        expired = False
        for doc in documents:
            evidence = doc['evidence']
            observed, available, expires = [datetime.fromisoformat(evidence[key]) for key in ['observedAt', 'availableAt', 'expiresAt']]
            assert observed <= available <= clock and available < expires
            expired |= expires <= clock
        assert expired == (case['category'] == 'expired')


@pytest.mark.parametrize('field,value', [('dataMode', 'real'), ('referenceClock', 'not-a-time'), ('endpoint', 'https://untrusted.invalid')])
def test_corpus_cannot_claim_real_mode_or_introduce_an_execution_endpoint(field, value):
    body = copy.deepcopy(CORPUS)
    body[field] = value
    with pytest.raises(ValidationError):
        validate('current-diagnosis-eval', body)


def test_fixture_annotations_cannot_claim_human_approval():
    body = copy.deepcopy(CORPUS)
    body['cases'][0]['annotation']['status'] = 'human-approved'
    with pytest.raises(ValidationError):
        validate('current-diagnosis-eval', body)
