import json
from pathlib import Path

import pytest
from jsonschema import ValidationError
import yaml

from test_source_endpoints import ROOT, validate


def fixture(name):
    return json.loads((ROOT / f"contracts/examples/v2/{name}.json").read_text(encoding="utf-8"))


@pytest.mark.parametrize("name", [
    "registered-item-sync",
    "registered-item-scan-run",
    "registered-item-scan-run-page",
    "registered-item-scan-run-read",
])
def test_registered_item_scan_examples_match_the_contract(name):
    validate(name, fixture(name))


@pytest.mark.parametrize("field", [
    "tenantId",
    "endpoint",
    "address",
    "credential",
    "hostGroupOverride",
    "permissions",
    "authorization",
])
def test_sync_receipt_does_not_accept_authority_or_connection_overrides(field):
    body = fixture("registered-item-sync")
    body[field] = "forged"
    with pytest.raises(ValidationError):
        validate("registered-item-sync", body)


@pytest.mark.parametrize("revision", [0, 101, "2"])
def test_scan_page_revision_is_a_bounded_integer(revision):
    body = fixture("registered-item-scan-run-page")
    body["configurationRevision"] = revision
    with pytest.raises(ValidationError):
        validate("registered-item-scan-run-page", body)


def test_failed_run_cannot_claim_a_complete_snapshot_or_retire_bindings():
    body = fixture("registered-item-scan-run")
    body.update(status="FAILED", snapshotComplete=False, completedAt="2026-10-06T06:10:02Z", retired=0)
    validate("registered-item-scan-run", body)
    body["retired"] = 1
    with pytest.raises(ValidationError):
        validate("registered-item-scan-run", body)


def test_page_cannot_exceed_its_query_bound():
    body = fixture("registered-item-scan-run-page")
    body["limit"] = 51
    with pytest.raises(ValidationError):
        validate("registered-item-scan-run-page", body)


def test_sync_is_an_explicit_bodyless_command_and_history_is_read_only():
    api = yaml.safe_load((ROOT / "contracts/openapi/platform-draft.yaml").read_text(encoding="utf-8"))
    paths = api["paths"]
    sync = paths["/api/v2/data-sources/{id}/connection/{revision}/items/sync"]["post"]
    assert sync["operationId"] == "syncRegisteredSourceItems"
    assert "requestBody" not in sync
    assert "browserSession" in sync["security"][1]
    assert "clients must not retry an uncertain result automatically" in sync["description"]

    page = paths["/api/v2/data-sources/{id}/connection/{revision}/items/runs"]["get"]
    assert page["operationId"] == "pageRegisteredItemScanRuns"
    assert {p["name"] for p in page["parameters"]} == {"limit", "after"}
    assert all(parameter["in"] == "query" for parameter in page["parameters"])
