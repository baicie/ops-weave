import json
from pathlib import Path
import pytest
from jsonschema import Draft202012Validator, FormatChecker, ValidationError

ROOT = Path(__file__).resolve().parents[2]
EXAMPLES = list((ROOT / "contracts/examples").glob("*.json"))

@pytest.mark.parametrize("example", EXAMPLES, ids=lambda p: p.stem)
def test_example_contract(example):
    definition = ROOT / "contracts/schemas/v1" / (example.stem + ".schema.json")
    schema = json.loads(definition.read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    Draft202012Validator(schema, format_checker=FormatChecker()).validate(json.loads(example.read_text(encoding="utf-8")))

def test_tool_arguments_reject_identity_override():
    tool = json.loads((ROOT / "contracts/tools/incident.get.tool.json").read_text(encoding="utf-8"))
    with pytest.raises(ValidationError):
        Draft202012Validator(tool["inputSchema"]).validate({"incidentId":"inc-1", "tenantId":"other"})
