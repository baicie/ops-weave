import copy
import json
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator, FormatChecker, ValidationError

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / "contracts/schemas/v1/metric-series-page.schema.json").read_text(encoding="utf-8"))
EXAMPLE = json.loads((ROOT / "contracts/examples/metric-series-page.json").read_text(encoding="utf-8"))
VALIDATOR = Draft202012Validator(SCHEMA, format_checker=FormatChecker())


def test_metric_series_example_and_statuses():
    VALIDATOR.check_schema(SCHEMA)
    VALIDATOR.validate(EXAMPLE)
    for kind in ("STALE", "NO_DATA", "PARTIAL"):
        page = copy.deepcopy(EXAMPLE)
        page["status"].update(kind=kind, fresh=False, partial=kind == "PARTIAL")
        if kind != "STALE":
            page["series"] = []
            page["status"]["lastPointAt"] = None
        VALIDATOR.validate(page)


@pytest.mark.parametrize("field", ["sourceInstanceId", "dataMode", "externalItemId", "mappingRevision", "unit"])
def test_series_requires_provenance(field):
    page = copy.deepcopy(EXAMPLE)
    del page["series"][0][field]
    with pytest.raises(ValidationError):
        VALIDATOR.validate(page)


@pytest.mark.parametrize("point", [[-1, "0.4"], [1, "NaN"], [1, 0.4], [1, "0.4", "extra"]])
def test_series_rejects_invalid_points(point):
    page = copy.deepcopy(EXAMPLE)
    page["series"][0]["points"] = [point]
    with pytest.raises(ValidationError):
        VALIDATOR.validate(page)


def test_series_rejects_false_completeness_and_unbounded_rows():
    page = copy.deepcopy(EXAMPLE)
    page["status"]["partial"] = True
    with pytest.raises(ValidationError):
        VALIDATOR.validate(page)
    page = copy.deepcopy(EXAMPLE)
    page["series"][0]["points"] *= 501
    with pytest.raises(ValidationError):
        VALIDATOR.validate(page)
