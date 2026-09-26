import json
from pathlib import Path
import pytest
from jsonschema import Draft202012Validator, FormatChecker, ValidationError
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = Registry().with_resources((p.as_uri(), Resource.from_contents(json.loads(p.read_text(encoding="utf-8"))))
    for p in (ROOT / "contracts/schemas").rglob("*.schema.json"))

def validate(name, value):
    schema = ROOT / f"contracts/schemas/v1/{name}.schema.json"
    Draft202012Validator({"$ref": schema.as_uri()}, registry=REGISTRY, format_checker=FormatChecker()).validate(value)

@pytest.mark.parametrize("field,value", [("limit", 101), ("limit", 0), ("after", "1-1-1-1-1"), ("q", "x" * 101),
    ("q", "bad\nsearch"), ("lifecycle", "*"), ("type", "../any"), ("tenantId", "other"), ("permissions", "entity.read")])
def test_entity_query_rejects_unbounded_or_untrusted_fields(field, value):
    query = json.loads((ROOT / "contracts/examples/entity-page-query.json").read_text())
    query[field] = value
    with pytest.raises(ValidationError): validate("entity-page-query", query)

def test_entity_page_has_bounded_items_and_no_invented_total():
    page = json.loads((ROOT / "contracts/examples/entity-page.json").read_text())
    page["items"] *= 101
    with pytest.raises(ValidationError): validate("entity-page", page)
    page["items"] = []; page["total"] = 10000
    with pytest.raises(ValidationError): validate("entity-page", page)
