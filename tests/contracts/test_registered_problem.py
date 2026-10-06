import copy
import json
import re
from pathlib import Path

import pytest
import yaml
from jsonschema import ValidationError

from test_source_endpoints import ROOT, validate


def fixture(name):
    return json.loads((ROOT / f"contracts/examples/v2/{name}.json").read_text(encoding="utf-8"))


@pytest.mark.parametrize("name", ["registered-problem", "registered-problem-page"])
def test_registered_problem_examples_match_the_contract(name):
    validate(name, fixture(name))


@pytest.mark.parametrize("field", [
    "endpoint", "address", "credential", "permissions", "authorization", "hostGroupOverride"
])
def test_problem_item_does_not_accept_identity_or_connection_overrides(field):
    body = fixture("registered-problem")
    body[field] = "forged"
    with pytest.raises(ValidationError):
        validate("registered-problem", body)


@pytest.mark.parametrize("field", ["tenantId", "endpoint", "address", "credential", "permissions", "authorization"])
def test_problem_page_does_not_accept_identity_or_connection_overrides(field):
    body = fixture("registered-problem-page")
    body[field] = "forged"
    with pytest.raises(ValidationError):
        validate("registered-problem-page", body)


@pytest.mark.parametrize("patch", [
    {"configurationRevision": 0},
    {"configurationRevision": 101},
    {"hostGroupIds": []},
    {"hostGroupIds": ["0"]},
    {"scopeDigest": "latest"},
    {"query": {"from": 1, "till": 2, "afterEventId": None, "limit": 101}},
    {"query": {"from": 1, "till": 2, "afterEventId": "0", "limit": 25}},
])
def test_problem_page_has_bounded_connection_and_query_scope(patch):
    body = fixture("registered-problem-page")
    body.update(copy.deepcopy(patch))
    with pytest.raises(ValidationError):
        validate("registered-problem-page", body)


def test_problem_page_openapi_operation_is_closed_and_read_only():
    api = yaml.safe_load((ROOT / "contracts/openapi/platform-draft.yaml").read_text(encoding="utf-8"))
    operation = api["paths"]["/api/v2/data-sources/{sourceId}/connection/{revision}/problems"]["get"]
    assert operation["operationId"] == "readRegisteredProblems"
    assert {p["name"] for p in operation["parameters"]} == {"sourceId", "revision", "from", "till", "afterEventId", "limit"}
    assert "requestBody" not in operation
    response = operation["responses"]["200"]["content"]["application/json"]["schema"]
    assert response["$ref"] == "../schemas/v2/registered-problem-page.schema.json"
    text = (ROOT / "contracts/openapi/platform-draft.yaml").read_text(encoding="utf-8")
    assert len(re.findall(r"operationId: readRegisteredProblems\b", text)) == 1


def test_recovery_unknown_keeps_the_missing_recovery_gap():
    body = fixture("registered-problem")
    body.update(state="RECOVERY_UNKNOWN", gaps=["RECOVERY_EVENT_UNAVAILABLE"])
    validate("registered-problem", body)
