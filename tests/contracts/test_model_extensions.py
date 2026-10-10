import json
from pathlib import Path

import pytest
import yaml
from jsonschema import Draft202012Validator, FormatChecker, ValidationError
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / "contracts" / "schemas" / "v1"
REGISTRY = Registry().with_resources((p.as_uri(), Resource.from_contents(json.loads(p.read_text(encoding="utf-8"))))
    for p in SCHEMAS.glob("*.schema.json"))


def validate(name, value):
    schema = SCHEMAS / f"{name}.schema.json"
    Draft202012Validator({"$ref": schema.as_uri()}, registry=REGISTRY, format_checker=FormatChecker()).validate(value)


def field():
    return {"id": "business_code", "label": "业务编码", "type": "TEXT", "required": False, "maxLength": 64}


def extension():
    return {
        "schemaVersion": "1.0",
        "base": {"id": "builtin.host", "revision": 1},
        "revision": 1,
        "digest": "sha256:" + "a" * 64,
        "fields": [field()],
    }


def entry(state="PUBLISHED", edit_version=0):
    return {"extension": extension(), "state": state, "editVersion": edit_version, "updatedAt": "2026-10-10T00:00:00Z"}


def test_extension_field_and_version_are_closed():
    validate("model-extension-field", field())
    validate("model-extension", extension())


@pytest.mark.parametrize("patch", [
    {"id": "tenant_id"}, {"id": "constructor"}, {"id": "__proto__"}, {"id": "source_ref"},
    {"id": "BusinessCode"}, {"id": "1code"}, {"id": "x" * 49}, {"type": "SQL"}, {"type": "JSON"},
    {"required": True}, {"required": "no"}, {"maxLength": 2049}, {"maxLength": 0},
    {"choices": ["a"]}, {"min": 0}, {"max": 10}, {"unexpected": 1},
])
def test_extension_field_rejects_unsafe_or_inapplicable_values(patch):
    value = field()
    value.update(patch)
    with pytest.raises(ValidationError):
        validate("model-extension-field", value)


def test_enum_and_numeric_fields_keep_type_specific_bounds():
    enum = {"id": "criticality", "label": "重要等级", "type": "ENUM", "required": False,
            "maxLength": 32, "choices": ["high", "medium", "low"]}
    validate("model-extension-field", enum)
    for patch in [{"choices": None}, {"choices": []}, {"choices": ["a", "a"]}, {"maxLength": None}]:
        broken = dict(enum)
        broken.update(patch)
        with pytest.raises(ValidationError):
            validate("model-extension-field", broken)
    number = {"id": "quota", "label": "配额", "type": "INTEGER", "required": False, "min": 0, "max": 100}
    validate("model-extension-field", number)
    for patch in [{"maxLength": 8}, {"choices": ["1"]}]:
        broken = dict(number)
        broken.update(patch)
        with pytest.raises(ValidationError):
            validate("model-extension-field", broken)
    text = dict(field())
    text.update({"min": 0})
    with pytest.raises(ValidationError):
        validate("model-extension-field", text)


def test_extension_rejects_required_or_malformed_fields():
    value = extension()
    value["fields"][0]["required"] = True
    with pytest.raises(ValidationError):
        validate("model-extension", value)
    value = extension()
    value["base"] = {"id": "builtin/../../escape", "revision": 1}
    with pytest.raises(ValidationError):
        validate("model-extension", value)
    value = extension()
    value["revision"] = 0
    with pytest.raises(ValidationError):
        validate("model-extension", value)
    value = extension()
    value["digest"] = "sha256:invalid"
    with pytest.raises(ValidationError):
        validate("model-extension", value)


def test_extension_command_is_closed_and_rejects_tenant_actor():
    validate("model-extension-command", {
        "base": {"id": "builtin.host", "revision": 1},
        "fields": [field()],
        "expectedEditVersion": 0,
    })
    for patch in [{"tenantId": "tenant-a"}, {"actor": "operator"}, {"base": {"id": "builtin.host"}},
                  {"base": {"id": "custom.widget", "revision": 0}}, {"expectedEditVersion": -1},
                  {"expectedEditVersion": 1000001}, {"fields": [field()] * 33}, {"unexpected": True}]:
        command = {"base": {"id": "builtin.host", "revision": 1}, "fields": [field()], "expectedEditVersion": 0}
        command.update(patch)
        with pytest.raises(ValidationError):
            validate("model-extension-command", command)


def test_publish_command_requires_digest_and_edit_version():
    validate("model-extension-publish-command", {
        "base": {"id": "builtin.host", "revision": 1},
        "expectedEditVersion": 1,
        "digest": "sha256:" + "a" * 64,
    })
    for patch in [{"tenantId": "tenant-a"}, {"expectedEditVersion": 0}, {"digest": "sha256:oops"},
                  {"digest": "md5:" + "a" * 32}, {"extra": 1}]:
        command = {"base": {"id": "builtin.host", "revision": 1}, "expectedEditVersion": 1, "digest": "sha256:" + "a" * 64}
        command.update(patch)
        with pytest.raises(ValidationError):
            validate("model-extension-publish-command", command)


def test_entry_keeps_edit_version_separate_from_immutable_revision():
    validate("model-extension-entry", entry())
    validate("model-extension-entry", entry("DRAFT", 1))
    for patch in [{"state": "DRAFT", "editVersion": 0}, {"state": "PUBLISHED", "editVersion": 1},
                  {"state": "ARCHIVED", "editVersion": 0}, {"updatedAt": "yesterday"}, {"extra": 1}]:
        value = entry()
        value.update(patch)
        with pytest.raises(ValidationError):
            validate("model-extension-entry", value)


def test_page_is_bounded_and_closed():
    validate("model-extension-page", {
        "schemaVersion": "1.0", "storage": "postgres",
        "published": {"items": [entry()], "truncated": False},
        "drafts": {"items": [entry("DRAFT", 1)], "truncated": False},
    })
    for patch in [{"storage": ""}, {"schemaVersion": "2.0"},
                  {"published": {"items": [entry()] * 51, "truncated": True}},
                  {"drafts": {"items": [], "truncated": "no"}}, {"extra": {}}]:
        page = {
            "schemaVersion": "1.0", "storage": "postgres",
            "published": {"items": [entry()], "truncated": False},
            "drafts": {"items": [], "truncated": False},
        }
        page.update(patch)
        with pytest.raises(ValidationError):
            validate("model-extension-page", page)


def test_extension_openapi_is_registered_with_closed_errors():
    api = yaml.safe_load((ROOT / "contracts/openapi/platform-draft.yaml").read_text(encoding="utf-8"))
    listing = api["paths"]["/api/v1/catalog/extensions"]
    assert {"$ref": "#/components/parameters/clientRequestId"} in listing["parameters"]
    assert listing["get"]["operationId"] == "listModelExtensions"
    assert listing["get"]["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith("model-extension-page.schema.json")
    assert set(listing["get"]["responses"]) == {"200", "401", "403", "503"}
    drafts = api["paths"]["/api/v1/catalog/extensions/drafts"]
    assert {"$ref": "#/components/parameters/clientRequestId"} in drafts["parameters"]
    assert drafts["post"]["operationId"] == "saveModelExtensionDraft"
    assert drafts["post"]["requestBody"]["content"]["application/json"]["schema"]["$ref"].endswith("model-extension-command.schema.json")
    assert drafts["post"]["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith("model-extension-entry.schema.json")
    assert set(drafts["post"]["responses"]) == {"200", "400", "401", "403", "404", "409", "503"}
    publish = api["paths"]["/api/v1/catalog/extensions/publish"]
    assert {"$ref": "#/components/parameters/clientRequestId"} in publish["parameters"]
    assert publish["post"]["operationId"] == "publishModelExtension"
    assert publish["post"]["requestBody"]["content"]["application/json"]["schema"]["$ref"].endswith("model-extension-publish-command.schema.json")
    assert set(publish["post"]["responses"]) == {"200", "400", "401", "403", "404", "409", "503"}
    text = (ROOT / "contracts/openapi/platform-draft.yaml").read_text(encoding="utf-8")
    assert "never accepts a tenant or actor" in text
    assert "base model body" in text
