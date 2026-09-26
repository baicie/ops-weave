import json
from pathlib import Path
import pytest
from jsonschema import Draft202012Validator, FormatChecker, ValidationError
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[2]
EXAMPLES = list((ROOT / "contracts/examples").glob("*.json"))
REGISTRY = Registry().with_resources((p.as_uri(), Resource.from_contents(json.loads(p.read_text(encoding="utf-8"))))
    for p in (ROOT / "contracts/schemas").rglob("*.schema.json"))

def test_datetime_checker_is_available_and_enforced():
    checker = FormatChecker()
    assert not checker.conforms("yesterday", "date-time"), "Install complete requirements-dev.txt; date-time checks must not be silently skipped"
    assert not checker.conforms("2026-02-30T12:00:00Z", "date-time")
    assert checker.conforms("2026-09-25T12:00:00.123456789Z", "date-time")

@pytest.mark.parametrize("example", EXAMPLES, ids=lambda p: p.stem)
def test_example_contract(example):
    definition = ROOT / "contracts/schemas/v1" / (example.stem + ".schema.json")
    schema = json.loads(definition.read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    Draft202012Validator({"$ref": definition.as_uri()}, registry=REGISTRY, format_checker=FormatChecker()).validate(json.loads(example.read_text(encoding="utf-8")))

def test_tool_arguments_reject_identity_override():
    tool = json.loads((ROOT / "contracts/tools/incident.get.tool.json").read_text(encoding="utf-8"))
    with pytest.raises(ValidationError):
        Draft202012Validator(tool["inputSchema"]).validate({"incidentId":"inc-1", "tenantId":"other"})
