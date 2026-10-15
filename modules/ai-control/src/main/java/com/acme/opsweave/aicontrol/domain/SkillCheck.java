package com.acme.opsweave.aicontrol.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The deterministic Skill check (M6, ADR-105 §2.6): run the draft's own evaluation cases against
 * its own schemas without calling any model.
 *
 * <p>Every case is an input and an expected output. The check verifies — structurally and
 * deterministically — that the output schema is well-formed JSON with local references only, that
 * each expected output parses as JSON and satisfies that schema (required members present, no member
 * outside the declared properties, declared types respected) and that the case list itself is well
 * formed. Nothing here consults a model, a tool or the network, so the report is a pure function of
 * the content: the same draft always produces the same report, byte for byte.
 *
 * <p>The real-model comparison is <em>explicitly not run</em>: the report always carries {@code
 * modelComparison = "not-run"} and never fabricates a quality verdict. A draft may only be published
 * after its deterministic check passes; a passing deterministic check is not a quality claim about
 * model output.
 */
public record SkillCheck(String skillId, String version, String contentDigest, Result result,
                         List<Case> cases, String modelComparison) {

    /** The overall deterministic verdict. */
    public enum Result {
        PASSED, FAILED;

        public boolean passed() {
            return this == PASSED;
        }
    }

    /** One case verdict; {@code detail} is a closed reason code, never content. */
    public record Case(String id, Result result, String detail) {
        public Case {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(detail, "detail");
        }
    }

    /** Closed detail codes of a verdict. */
    public static final class Detail {
        private Detail() {}

        public static final String OK = "OK";
        public static final String SCHEMA_INVALID = "SCHEMA_INVALID";
        public static final String SCHEMA_REMOTE_REF = "SCHEMA_REMOTE_REF";
        public static final String SCHEMA_NOT_OBJECT = "SCHEMA_NOT_OBJECT";
        public static final String EXPECTED_NOT_JSON = "EXPECTED_NOT_JSON";
        public static final String EXPECTED_NOT_OBJECT = "EXPECTED_NOT_OBJECT";
        public static final String EXPECTED_MISSING_MEMBER = "EXPECTED_MISSING_MEMBER";
        public static final String EXPECTED_UNKNOWN_MEMBER = "EXPECTED_UNKNOWN_MEMBER";
        public static final String EXPECTED_TYPE_MISMATCH = "EXPECTED_TYPE_MISMATCH";
        public static final String TEMPLATE_LIMIT_EXCEEDED = "TEMPLATE_LIMIT_EXCEEDED";
        public static final String TOOLS_NOT_ALLOWED = "TOOLS_NOT_ALLOWED";
        public static final String CONTENT_REFERENCE_FORBIDDEN = "CONTENT_REFERENCE_FORBIDDEN";
        public static final String SECRET_MARKER = "SECRET_MARKER";
    }

    /** The constant disclosure: no real model is consulted by this check. */
    public static final String MODEL_COMPARISON = "not-run";

    public SkillCheck {
        Objects.requireNonNull(skillId, "skillId");
        Objects.requireNonNull(version, "version");
        SkillDraft.requireSkillId(skillId);
        SkillDraft.requireVersion(version);
        Objects.requireNonNull(contentDigest, "contentDigest");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(cases, "cases");
        if (!MODEL_COMPARISON.equals(modelComparison)) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        if (cases.isEmpty()) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        boolean allPassed = cases.stream().allMatch(c -> c.result() == Result.PASSED);
        if (allPassed != result.passed()) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
    }

    /**
     * Execute the deterministic check over one draft content. The report is a pure function of the
     * content — no clock, no model, no tool and no network access is involved. The template and
     * budget inheritance, the tool whitelist, the no-path/no-URL content rule and the no-secret rule
     * are checked here before any case is evaluated.
     */
    public static SkillCheck run(String skillId, String version, SkillContent content) {
        Objects.requireNonNull(content, "content");
        var gate = contentGate(content);
        if (gate != null) return failed(skillId, version, content, gate);
        Map<String, Object> schema;
        try {
            schema = StrictJson.parseObject(content.outputSchema());
        } catch (IllegalArgumentException invalid) {
            return failed(skillId, version, content, new Case("schema", Result.FAILED, Detail.SCHEMA_INVALID));
        }
        if (!SkillContent.localSchemaReferences(schema)) {
            return failed(skillId, version, content, new Case("schema", Result.FAILED, Detail.SCHEMA_REMOTE_REF));
        }
        if (schema.get("type") instanceof String type && !"object".equals(type)) {
            return failed(skillId, version, content, new Case("schema", Result.FAILED, Detail.SCHEMA_NOT_OBJECT));
        }
        var properties = schema.get("properties") instanceof Map<?, ?> map ? map : Map.of();
        var required = schema.get("required") instanceof List<?> list ? list : List.of();
        var cases = new ArrayList<Case>();
        for (var test : content.tests()) {
            cases.add(checkCase(test, properties, required));
        }
        var result = cases.stream().allMatch(c -> c.result() == Result.PASSED) ? Result.PASSED : Result.FAILED;
        return new SkillCheck(skillId, version, content.contentDigest(), result, List.copyOf(cases), MODEL_COMPARISON);
    }

    /** The content gate: template limits, tool whitelist, no path/URL reference, no secret marker. */
    private static Case contentGate(SkillContent content) {
        var template = content.template();
        if (!template.supports(content.allowedTools(), content.maxToolCalls(), content.maxEvidence(),
            content.maxContextBytes(), content.deadlineMs())) {
            boolean toolsFit = content.allowedTools().size() <= template.tools().size()
                && template.tools().containsAll(content.allowedTools());
            return new Case("content", Result.FAILED,
                toolsFit ? Detail.TEMPLATE_LIMIT_EXCEEDED : Detail.TOOLS_NOT_ALLOWED);
        }
        if (!SkillContent.freeOfPathOrUrl(content.prompt()) || !SkillContent.freeOfPathOrUrl(content.outputSchema())) {
            return new Case("content", Result.FAILED, Detail.CONTENT_REFERENCE_FORBIDDEN);
        }
        if (SkillContent.hasSecretMarker(content.prompt()) || SkillContent.hasSecretMarker(content.outputSchema())) {
            return new Case("content", Result.FAILED, Detail.SECRET_MARKER);
        }
        for (var test : content.tests()) {
            if (!SkillContent.freeOfPathOrUrl(test.question()) || !SkillContent.freeOfPathOrUrl(test.expectedOutput())
                || SkillContent.hasSecretMarker(test.question()) || SkillContent.hasSecretMarker(test.expectedOutput())) {
                return new Case("content", Result.FAILED, Detail.SECRET_MARKER);
            }
        }
        return null;
    }

    private static Case checkCase(SkillContent.TestCase test, Map<?, ?> properties, List<?> required) {
        Object expected;
        try {
            expected = StrictJson.parse(test.expectedOutput());
        } catch (IllegalArgumentException invalid) {
            return new Case(test.id(), Result.FAILED, Detail.EXPECTED_NOT_JSON);
        }
        if (!(expected instanceof Map<?, ?> members)) {
            return new Case(test.id(), Result.FAILED, Detail.EXPECTED_NOT_OBJECT);
        }
        for (var name : required) {
            if (!members.containsKey(String.valueOf(name))) {
                return new Case(test.id(), Result.FAILED, Detail.EXPECTED_MISSING_MEMBER);
            }
        }
        for (var key : members.keySet()) {
            if (!properties.isEmpty() && !properties.containsKey(String.valueOf(key))) {
                return new Case(test.id(), Result.FAILED, Detail.EXPECTED_UNKNOWN_MEMBER);
            }
            var declared = properties.get(String.valueOf(key));
            if (declared instanceof Map<?, ?> property && property.get("type") instanceof String type
                && !matchesType(members.get(key), type)) {
                return new Case(test.id(), Result.FAILED, Detail.EXPECTED_TYPE_MISMATCH);
            }
        }
        return new Case(test.id(), Result.PASSED, Detail.OK);
    }

    private static boolean matchesType(Object value, String type) {
        return switch (type) {
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            case "string" -> value instanceof String;
            case "number" -> value instanceof Double;
            case "integer" -> value instanceof Double d && d == Math.rint(d) && !d.isInfinite();
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            default -> true;
        };
    }

    private static SkillCheck failed(String skillId, String version, SkillContent content, Case single) {
        return new SkillCheck(skillId, version, content.contentDigest(), Result.FAILED, List.of(single), MODEL_COMPARISON);
    }
}
