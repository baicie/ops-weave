from pathlib import Path
import yaml
from jsonschema import Draft202012Validator, FormatChecker, ValidationError
import pytest


def test_all_platform_paths_declare_optional_correlation_metadata():
    api = yaml.safe_load(Path('contracts/openapi/platform-draft.yaml').read_text(encoding='utf-8'))
    for path, item in api['paths'].items():
        assert path.startswith('/api/v1/')
        assert {'$ref': '#/components/parameters/clientRequestId'} in item['parameters']
    parameter = api['components']['parameters']['clientRequestId']
    assert parameter['in'] == 'header' and parameter['required'] is False
    validator = Draft202012Validator(parameter['schema'], format_checker=FormatChecker())
    validator.validate(parameter['example'])
    with pytest.raises(ValidationError):
        validator.validate('tenant=other;actor=admin')
