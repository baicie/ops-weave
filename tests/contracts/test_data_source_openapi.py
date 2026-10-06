from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[2]
OPENAPI = ROOT / "contracts/openapi/platform-draft.yaml"


def _section(path: str) -> str:
    text = OPENAPI.read_text(encoding="utf-8")
    marker = f"  {path}:\n"
    start = text.index(marker)
    next_path = re.search(r"\n  /[^\n]+:\n", text[start + len(marker) :])
    end = start + len(marker) + next_path.start() if next_path else len(text)
    return text[start:end]


def test_data_source_paths_are_registered_with_closed_response_refs():
    expected = {
        "/api/v2/data-sources": {
            "get": "source-instance-page.schema.json",
            "post": "source-instance-create-command.schema.json",
        },
        "/api/v2/data-sources/{id}": {
            "get": "source-instance-read.schema.json",
            "patch": "source-instance-command-receipt.schema.json",
        },
        "/api/v2/data-sources/{id}/configurations": {
            "get": "source-instance-configurations.schema.json",
        },
        "/api/v2/data-sources/{id}/test": {
            "post": "source-inspection-read.schema.json",
        },
        "/api/v2/data-sources/{id}/connection-check": {
            "post": "source-inspection-read.schema.json",
        },
        "/api/v2/data-sources/{id}/discover": {
            "post": "source-inspection-read.schema.json",
        },
        "/api/v2/data-sources/{id}/discover-metrics": {
            "post": "source-metric-discovery-read.schema.json",
        },
        "/api/v2/data-sources/{id}/metric-discoveries": {
            "post": "source-metric-page-read.schema.json",
        },
        "/api/v2/data-sources/{id}/connection/{revision}/workflow-metrics": {
            "get": "workflow-metric-source-page.schema.json",
        },
        "/api/v2/data-sources/{id}/connection/{revision}/workflow-logs": {
            "get": "workflow-log-source-page.schema.json",
        },
        "/api/v2/data-sources/{id}/inspections": {
            "get": "source-inspection-page.schema.json",
        },
        "/api/v2/data-sources/{id}/inspections/{requestId}": {
            "get": "source-inspection-read.schema.json",
        },
        "/api/v2/data-sources/{id}/connection-checks": {
            "get": "source-inspection-page.schema.json",
        },
        "/api/v2/data-sources/{id}/commands/{requestId}": {
            "get": "source-instance-command-receipt.schema.json",
        },
        "/api/v2/data-sources/connections": {
            "post": "source-connection-receipt.schema.json",
        },
        "/api/v2/data-sources/{id}/connection": {
            "get": "source-connection-read.schema.json",
            "patch": "source-connection-receipt.schema.json",
        },
        "/api/v2/data-sources/{id}/connection/history": {
            "get": "source-connection-history.schema.json",
        },
        "/api/v2/data-sources/{id}/connection/commands/{requestId}": {
            "get": "source-connection-receipt.schema.json",
        },
        "/api/v2/data-sources/credentials": {
            "get": "source-credential-page.schema.json",
            "post": "source-credential-receipt.schema.json",
        },
        "/api/v2/data-sources/credentials/{id}": {
            "get": "source-credential-read.schema.json",
            "patch": "source-credential-receipt.schema.json",
        },
        "/api/v2/data-sources/credentials/{id}/versions": {
            "get": "source-credential-version-page.schema.json",
        },
        "/api/v2/data-sources/credentials/{id}/versions/{revision}/revoke": {
            "post": "source-credential-receipt.schema.json",
        },
        "/api/v2/data-sources/credentials/{id}/commands/{requestId}": {
            "get": "source-credential-command-read.schema.json",
        },
    }

    text = OPENAPI.read_text(encoding="utf-8")
    for path, methods in expected.items():
        section = _section(path)
        for method, schema in methods.items():
            method_block = re.search(
                rf"\n    {method}:\n(?P<body>.*?)(?=\n    (?:get|post|patch|put|delete|options|head|trace):|\Z)",
                section,
                re.DOTALL,
            )
            assert method_block, f"missing OpenAPI operation: {method.upper()} {path}"
            assert f"../schemas/v2/{schema}" in method_block.group("body")

    for schema in {
        schema for methods in expected.values() for schema in methods.values()
    }:
        assert (ROOT / "contracts/schemas/v2" / schema).is_file(), schema

    assert text.count("operationId: pageSourceInstances") == 1
    assert text.count("operationId: discoverSourceFields") == 1
    assert text.count("operationId: discoverSourceMetrics") == 1
    assert text.count("operationId: pageSourceMetricDiscovery") == 1
    assert text.count("operationId: checkSourceConnection") == 1


def test_workflow_source_and_metric_binding_paths_are_registered():
    expected = {
        "/api/v2/data-sources/{id}/connection/{revision}/workflow-metrics": (
            "get", "workflow-metric-source-page.schema.json"
        ),
        "/api/v2/data-sources/{id}/connection/{revision}/workflow-logs": (
            "get", "workflow-log-source-page.schema.json"
        ),
        "/api/v2/metric-bindings": ("get", "metric-mapping-page.schema.json"),
        "/api/v2/metric-bindings/{source}/{item}": (
            "get", "metric-mapping-read.schema.json"
        ),
        "/api/v2/metric-bindings/{source}/{item}/mapping": (
            "post", "metric-mapping-receipt-read.schema.json"
        ),
        "/api/v2/metric-bindings/{source}/{item}/mapping/commands/{requestId}": (
            "get", "metric-mapping-receipt-read.schema.json"
        ),
    }
    for path, (method, schema) in expected.items():
        section = _section(path)
        method_block = re.search(
            rf"\n    {method}:\n(?P<body>.*?)(?=\n    (?:get|post|patch|put|delete|options|head|trace):|\Z)",
            section,
            re.DOTALL,
        )
        assert method_block, f"missing OpenAPI operation: {method.upper()} {path}"
        assert f"../schemas/v2/{schema}" in method_block.group("body")

    text = OPENAPI.read_text(encoding="utf-8")
    for operation_id in (
        "pageWorkflowMetricSources",
        "pageWorkflowLogSources",
        "pageMetricBindings",
        "readMetricBinding",
        "configureMetricBinding",
        "getMetricBindingReceipt",
    ):
        assert text.count(f"operationId: {operation_id}") == 1
