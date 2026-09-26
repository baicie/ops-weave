import json
import pytest
from jsonschema import ValidationError
from test_entity_page import ROOT, validate

@pytest.mark.parametrize("field,value", [("version", 0), ("limit", 26), ("limit", 0), ("from", -1), ("till", 253402300800),
    ("source", "source\ninvalid"), ("eventId", "01"), ("eventId", "101"), ("after", "1-1-1-1-1"), ("asOf", "tomorrow"), ("tenantId", "other"), ("permission", "incident.read")])
def test_problem_history_query_bounds(field, value):
    item = json.loads((ROOT / "contracts/examples/problem-observation-query.json").read_text())
    item[field] = value
    with pytest.raises(ValidationError): validate("problem-observation-query", item)

@pytest.mark.parametrize("field,value", [("sourceContract", "unknown"), ("dataMode", "live"), ("gaps", ["invented"]),
    ("firstReceivedAt", "2026-02-30T00:00:00Z"), ("id", "bad"), ("rawPayload", {"secret": "not allowed"})])
def test_problem_history_record_bounds(field, value):
    item = json.loads((ROOT / "contracts/examples/problem-observation.json").read_text())
    item[field] = value
    with pytest.raises(ValidationError): validate("problem-observation", item)

def test_problem_history_is_bounded_and_does_not_claim_complete_vendor_history():
    item = json.loads((ROOT / "contracts/examples/problem-observation-page.json").read_text())
    item["items"] *= 26
    with pytest.raises(ValidationError): validate("problem-observation-page", item)
    item["items"] = []; item["coverage"] = "complete"
    with pytest.raises(ValidationError): validate("problem-observation-page", item)
    item["coverage"] = "retained-normalized-current-ownership"; item["gaps"] = []
    with pytest.raises(ValidationError): validate("problem-observation-page", item)
