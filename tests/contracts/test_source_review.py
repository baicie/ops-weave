import json
import pytest
from jsonschema import ValidationError
from test_entity_page import ROOT, validate

@pytest.mark.parametrize("field,value", [("tenantId", "injected"), ("actor", "forged"), ("sourceInstanceId", "untrusted"),
    ("expectedEntityVersion", 0), ("values", {}), ("values", {"lifecycle": "ACTIVE"}), ("values", {"ip": "x" * 129}),
    ("values", {"owner": "x\ny"}), ("observedAt", "2026-02-30T00:00:00Z"), ("externalId", "x" * 257)])
def test_import_boundary(field, value):
    item = json.loads((ROOT / "contracts/examples/source-review-import.json").read_text())
    item[field] = value
    with pytest.raises(ValidationError): validate("source-review-import", item)

@pytest.mark.parametrize("field,value", [("action", "MERGE"), ("expectedReviewVersion", 3), ("reason", ""), ("reason", "x" * 501),
    ("choices", {"owner": "AUTO"}), ("actor", "forged"), ("requestId", "not-an-id")])
def test_decision_boundary(field, value):
    item = json.loads((ROOT / "contracts/examples/source-review-decision.json").read_text())
    item[field] = value
    with pytest.raises(ValidationError): validate("source-review-decision", item)

def test_revoke_cannot_smuggle_a_new_field_choice():
    item = json.loads((ROOT / "contracts/examples/source-review-decision.json").read_text())
    item["action"] = "REVOKE"
    with pytest.raises(ValidationError): validate("source-review-decision", item)

def test_import_page_and_engine_are_fixed_and_bounded():
    item = json.loads((ROOT / "contracts/examples/source-review-page.json").read_text())
    item["items"] *= 26
    with pytest.raises(ValidationError): validate("source-review-page", item)
    item["items"] = []
    item["mapping"]["definition"]["nodes"][2]["config"] = {"script": "run"}
    with pytest.raises(ValidationError): validate("source-review-page", item)
