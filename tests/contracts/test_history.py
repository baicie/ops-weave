import copy
import json
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator, FormatChecker, ValidationError

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / "contracts/schemas/v1/metric-history-page.schema.json").read_text())
EXAMPLE = json.loads((ROOT / "contracts/examples/metric-history-page.json").read_text())
VALIDATOR = Draft202012Validator(SCHEMA, format_checker=FormatChecker())


@pytest.mark.parametrize("field,value", [("ns", -1), ("ns", 1000000000), ("value", "NaN"), ("value", 0.25), ("clock", -1)])
def test_history_rejects_invalid_point(field, value):
    page = copy.deepcopy(EXAMPLE)
    page["points"][0][field] = value
    with pytest.raises(ValidationError):
        VALIDATOR.validate(page)


def test_history_requires_bounded_page_and_honest_persistence():
    page = copy.deepcopy(EXAMPLE)
    page["points"] *= 501
    with pytest.raises(ValidationError):
        VALIDATOR.validate(page)
    page = copy.deepcopy(EXAMPLE)
    page["persistence"] = "stored"
    with pytest.raises(ValidationError):
        VALIDATOR.validate(page)


def test_unsigned_integer_decimal_string_keeps_precision():
    page = copy.deepcopy(EXAMPLE)
    page["points"][0]["value"] = "18446744073709551615"
    VALIDATOR.validate(page)
