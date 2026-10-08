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


def _assert_operation(path: str, method: str, schema: str, operation_id: str) -> None:
    section = _section(path)
    match = re.search(
        rf"\n    {method}:\n(?P<body>.*?)(?=\n    (?:get|post|patch|put|delete|options|head|trace):|\Z)",
        section,
        re.DOTALL,
    )
    assert match, f"missing OpenAPI operation: {method.upper()} {path}"
    body = match.group("body")
    assert f"operationId: {operation_id}" in body
    assert f"../schemas/{schema}" in body
    assert (ROOT / "contracts/schemas" / schema.split("/", 1)[0] / schema.split("/", 1)[1]).is_file()


def test_workflow_crud_runtime_and_stream_routes_are_registered():
    expected = [
        ("/api/v1/integrations/workflows", "get", "v2/workflow-page.schema.json", "pageWorkflows"),
        ("/api/v1/integrations/workflows/drafts", "post", "v2/workflow-draft-command.schema.json", "saveWorkflowDraft"),
        ("/api/v1/integrations/workflows/drafts/{id}/{revision}", "get", "v2/workflow-entry.schema.json", "getWorkflowDraft"),
        ("/api/v1/integrations/workflows/versions/{id}/{revision}", "get", "v2/workflow-entry.schema.json", "getWorkflowVersion"),
        ("/api/v1/integrations/workflows/comparisons", "post", "v2/workflow-comparison-request.schema.json", "compareWorkflowVersions"),
        ("/api/v1/integrations/workflows/publish", "post", "v2/workflow-publish-command.schema.json", "publishWorkflow"),
        ("/api/v1/integrations/workflows/preview", "post", "v2/workflow-evaluate-command.schema.json", "previewWorkflow"),
        ("/api/v1/integrations/workflows/run", "post", "v2/workflow-evaluate-command.schema.json", "runWorkflow"),
        ("/api/v1/integrations/workflows/runtime", "get", "v2/workflow-runtime.schema.json", "getWorkflowRuntime"),
        ("/api/v1/integrations/workflows/runtime/executions/{id}", "get", "v2/workflow-runtime-execution.schema.json", "getWorkflowRuntimeExecution"),
        ("/api/v1/integrations/workflows/runtime/execute", "post", "v2/workflow-runtime-execution.schema.json", "executeWorkflowRuntime"),
        ("/api/v1/integrations/workflows/runtime/{operation}", "post", "v2/workflow-runtime-control-receipt.schema.json", "controlWorkflowRuntime"),
        ("/api/v1/integrations/workflows/host-schedules/{operation}", "post", "v2/workflow-host-schedule-receipt.schema.json", "controlWorkflowHostSchedule"),
        ("/api/v1/integrations/workflows/metric-streams/{operation}", "post", "v2/workflow-metric-stream-receipt.schema.json", "controlWorkflowMetricStream"),
        ("/api/v1/integrations/workflows/log-streams/{operation}", "post", "v2/workflow-log-stream-receipt.schema.json", "controlWorkflowLogStream"),
    ]
    for path, method, schema, operation_id in expected:
        _assert_operation(path, method, schema, operation_id)


def test_workflow_output_routes_are_registered_with_closed_schemas():
    expected = [
        ("/api/v1/integrations/workflows/metric-outputs", "get", "v2/workflow-metric-output-capability.schema.json", "getWorkflowMetricOutputCapability"),
        ("/api/v1/integrations/workflows/metric-outputs", "post", "v2/workflow-metric-output-request.schema.json", "writeWorkflowMetricOutput"),
        ("/api/v1/integrations/workflows/metric-outputs/commands/{id}/points", "get", "v2/workflow-metric-output-points.schema.json", "readWorkflowMetricOutputPoints"),
        ("/api/v1/integrations/workflows/log-outputs/workflows/{id}/capability", "get", "v2/workflow-log-output-capability.schema.json", "getWorkflowLogOutputCapability"),
        ("/api/v1/integrations/workflows/log-outputs", "post", "v2/workflow-log-output-request.schema.json", "writeWorkflowLogOutput"),
        ("/api/v1/integrations/workflows/log-outputs/source", "post", "v2/workflow-log-source-output-request.schema.json", "writeWorkflowSourceLogOutput"),
        ("/api/v1/integrations/workflows/log-outputs/commands/{id}/records", "get", "v2/workflow-log-output-data.schema.json", "readWorkflowLogOutputRecords"),
        ("/api/v1/integrations/workflows/log-streams/workflows/{id}/batches/{batchId}/records", "get", "v2/workflow-log-stream-data.schema.json", "getWorkflowLogStreamRecords"),
    ]
    for path, method, schema, operation_id in expected:
        _assert_operation(path, method, schema, operation_id)


def test_metric_directory_routes_are_registered():
    _assert_operation(
        "/api/v1/metrics/definitions",
        "get",
        "v1/metric-definition-page.schema.json",
        "pageMetricDefinitions",
    )
    _assert_operation(
        "/api/v1/metrics/bindings",
        "get",
        "v1/metric-binding-page.schema.json",
        "pageMetricBindingDirectory",
    )


def test_operation_ids_are_unique_and_added_input_schemas_are_closed():
    text = OPENAPI.read_text(encoding="utf-8")
    operation_ids = re.findall(r"^      operationId: ([^\s]+)$", text, re.MULTILINE)
    assert len(operation_ids) == len(set(operation_ids))
    for name in (
        "workflow-draft-command.schema.json",
        "workflow-publish-command.schema.json",
        "workflow-evaluate-command.schema.json",
    ):
        schema = (ROOT / "contracts/schemas/v2" / name).read_text(encoding="utf-8")
        assert '"additionalProperties": false' in schema
