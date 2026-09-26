import json
import pytest
from jsonschema import ValidationError
from test_entity_page import ROOT, validate

@pytest.mark.parametrize("field,value", [("limit", 51), ("limit", 0), ("after", "../raw"), ("source", "a\nb"), ("from", -1),
    ("asOf", "yesterday"), ("tenantId", "other"), ("permissions", ["entity.read"])])
def test_observation_query_bounds(field, value):
    item = json.loads((ROOT / "contracts/examples/observation-query.json").read_text())
    item[field] = value
    with pytest.raises(ValidationError): validate("observation-query", item)

@pytest.mark.parametrize("field,value", [("mappingRevision", 0), ("timePrecision", "exact-legacy"), ("observedAt", "2026-02-30T00:00:00Z"),
    ("gaps", ["invented"]), ("rawRecordRef", "x" * 257), ("id", "bad\nid")])
def test_observation_record_bounds(field, value):
    item = json.loads((ROOT / "contracts/examples/observation.json").read_text())
    item[field] = value
    with pytest.raises(ValidationError): validate("observation", item)

def test_history_is_bounded_retained_data_not_complete_source():
    item = json.loads((ROOT / "contracts/examples/observation-page.json").read_text())
    item["items"] *= 51
    with pytest.raises(ValidationError): validate("observation-page", item)
    item["items"] = []; item["coverage"] = "complete"
    with pytest.raises(ValidationError): validate("observation-page", item)
