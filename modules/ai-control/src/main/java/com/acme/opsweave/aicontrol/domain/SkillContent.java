package com.acme.opsweave.aicontrol.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The closed configuration content of one platform-managed Skill version (M6, ADR-105).
 *
 * <p>A Skill is configuration, never code: an execution template from the registered Rust flow
 * templates, a Prompt, an output Schema, a tool whitelist and a budget. Nothing here is executable —
 * the Prompt and the Schema are opaque text, schemas may only reference themselves and no content
 * field may name a path or URL that would fetch or escape anything. The content cannot grant a
 * caller a single permission and cannot widen a budget past the template that binds it.
 *
 * <p>The content digest anchors exactly what was authored: the same bytes always produce the same
 * digest (length-prefixed SHA-256 over the ordered parts, the same scheme the Rust loader uses over
 * package bytes), so a published version stays verifiable long after the draft was edited.
 */
public record SkillContent(String executionTemplate, String prompt, String outputSchema,
                           List<String> allowedTools, String knowledgeScope, String modelPolicyRef,
                           String sideEffects, int maxToolCalls, int maxEvidence, int maxContextBytes,
                           long deadlineMs, List<TestCase> tests) {

    /** One deterministic evaluation case: an input and its expected output, no model involved. */
    public record TestCase(String id, String question, String expectedOutput) {
        public TestCase {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(question, "question");
            Objects.requireNonNull(expectedOutput, "expectedOutput");
            if (!id.matches("[a-z][a-z0-9-]{0,31}")) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            if (question.isBlank() || question.codePointCount(0, question.length()) > 2000) {
                throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            }
            if (expectedOutput.isBlank() || expectedOutput.length() > 8192) {
                throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            }
        }
    }

    /** The registered Rust flow templates and the limits each one inherits (ADR-105 §2.5). */
    public enum Template {
        READONLY_CURRENT_INCIDENT_DIAGNOSIS_V1("readonly-current-incident-diagnosis-v1",
            List.of("incident.get@2.0.0", "metric.summary@2.0.0", "evidence.get@2.0.0"), 4, 2, 65536, 55000),
        READONLY_INCIDENT_DIAGNOSIS_V1("readonly-incident-diagnosis-v1",
            List.of("incident.get@1.0.0", "metric.summary@1.0.0"), 8, 32, 65536, 60000);

        private final String id;
        private final List<String> tools;
        private final int maxToolCalls;
        private final int maxEvidence;
        private final int maxContextBytes;
        private final long deadlineMs;

        Template(String id, List<String> tools, int maxToolCalls, int maxEvidence, int maxContextBytes, long deadlineMs) {
            this.id = id;
            this.tools = tools;
            this.maxToolCalls = maxToolCalls;
            this.maxEvidence = maxEvidence;
            this.maxContextBytes = maxContextBytes;
            this.deadlineMs = deadlineMs;
        }

        public String id() {
            return id;
        }

        public List<String> tools() {
            return tools;
        }

        public static Template of(String id) {
            Objects.requireNonNull(id, "id");
            for (var template : values()) {
                if (template.id.equals(id)) return template;
            }
            throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }

        /** Whether this template's registered bounds admit the requested budget and tool set. */
        public boolean supports(List<String> tools, int toolCalls, int evidence, int contextBytes, long deadline) {
            if (tools == null || tools.isEmpty() || tools.size() > this.tools.size()) return false;
            if (new HashSet<>(tools).size() != tools.size()) return false;
            if (!this.tools.containsAll(tools)) return false;
            return toolCalls >= 1 && toolCalls <= maxToolCalls
                && evidence >= 1 && evidence <= maxEvidence
                && contextBytes >= 1024 && contextBytes <= maxContextBytes
                && deadline >= 1000 && deadline <= deadlineMs;
        }
    }

    public SkillContent {
        Objects.requireNonNull(executionTemplate, "executionTemplate");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(outputSchema, "outputSchema");
        Objects.requireNonNull(allowedTools, "allowedTools");
        Objects.requireNonNull(knowledgeScope, "knowledgeScope");
        Objects.requireNonNull(modelPolicyRef, "modelPolicyRef");
        Objects.requireNonNull(sideEffects, "sideEffects");
        Objects.requireNonNull(tests, "tests");
        // Shape bounds only; the template policy is checked by the deterministic validation so an
        // author can stage an out-of-template draft and see it refused before review.
        Template.of(executionTemplate);
        if (prompt.isBlank() || prompt.codePointCount(0, prompt.length()) > 16384) {
            throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }
        if (outputSchema.isBlank() || outputSchema.length() > 32768) {
            throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }
        if (allowedTools.isEmpty() || allowedTools.size() > 8) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        for (String tool : allowedTools) {
            if (tool == null || !tool.matches("[a-z][a-z0-9.]{0,31}@[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}")) {
                throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
            }
        }
        if (new HashSet<>(allowedTools).size() != allowedTools.size()) {
            throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }
        if (!"current".equals(knowledgeScope)) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        if (!"reasoning-structured-readonly".equals(modelPolicyRef)) {
            throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }
        if (!"forbidden".equals(sideEffects)) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        if (maxToolCalls < 1 || maxToolCalls > 8) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        if (maxEvidence < 1 || maxEvidence > 32) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        if (maxContextBytes < 1024 || maxContextBytes > 65536) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        if (deadlineMs < 1000 || deadlineMs > 60000) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        if (tests.isEmpty() || tests.size() > 20) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        var caseIds = new HashSet<String>();
        for (var test : tests) {
            Objects.requireNonNull(test, "test");
            if (!caseIds.add(test.id())) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        }
    }

    /** The bound template: every budget and tool must fit inside what this template supports. */
    public Template template() {
        return Template.of(executionTemplate);
    }

    /**
     * Content digest over the ordered authoring parts (ADR-105 §2.3): the same length-prefixed
     * SHA-256 scheme the Rust package loader uses, so both sides can anchor one version identically.
     */
    public String contentDigest() {
        var parts = new ArrayList<String>();
        parts.add("opsweave-skill-content-v1");
        parts.add(executionTemplate);
        parts.add(prompt);
        parts.add(outputSchema);
        parts.add(String.join(",", allowedTools));
        parts.add(knowledgeScope);
        parts.add(modelPolicyRef);
        parts.add(sideEffects);
        parts.add(Integer.toString(maxToolCalls));
        parts.add(Integer.toString(maxEvidence));
        parts.add(Integer.toString(maxContextBytes));
        parts.add(Long.toString(deadlineMs));
        for (var test : tests) {
            parts.add(test.id());
            parts.add(test.question());
            parts.add(test.expectedOutput());
        }
        try {
            var md = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                md.update(ByteBuffer.allocate(Long.BYTES).putLong(bytes.length).array());
                md.update(bytes);
            }
            return "sha256:" + java.util.HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException missing) {
            throw new IllegalStateException();
        }
    }

    /** Every {@code $ref}/{@code $dynamicRef} in the schema must stay local; remote fetch is refused. */
    static boolean localSchemaReferences(Object node) {
        if (node instanceof Map<?, ?> members) {
            for (var entry : members.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if ("$ref".equals(key) || "$dynamicRef".equals(key)) {
                    String target = entry.getValue() instanceof String text ? text : null;
                    if (target == null || !target.startsWith("#")) return false;
                } else if (!localSchemaReferences(entry.getValue())) {
                    return false;
                }
            }
            return true;
        }
        if (node instanceof List<?> items) {
            for (var item : items) {
                if (!localSchemaReferences(item)) return false;
            }
        }
        return true;
    }

    /**
     * A content field must never name a path or a fetchable URL: the registry stores inline text
     * only, so any file-like or remote reference is refused rather than resolved (ADR-105 §2.7). The
     * only tolerated URL-shaped token is the JSON Schema vocabulary identifier namespace, which is a
     * name, not a fetch — every {@code $ref} is separately required to stay local.
     */
    static boolean freeOfPathOrUrl(String text) {
        if (text == null) return true;
        String lowered = text.toLowerCase(java.util.Locale.ROOT)
            .replace("https://json-schema.org/", "")
            .replace("http://json-schema.org/", "");
        return !lowered.contains("http://") && !lowered.contains("https://")
            && !lowered.contains("file://") && !lowered.contains("ftp://");
    }

    /**
     * A content field must never carry a credential. This is a marker screen over a closed list of
     * secret shapes (key blocks, authorization headers, token assignments) — it refuses content that
     * looks like a secret rather than trying to prove the absence of one (ADR-105 §2.7).
     */
    static boolean hasSecretMarker(String text) {
        if (text == null) return false;
        String lowered = text.toLowerCase(java.util.Locale.ROOT);
        for (String marker : SECRET_MARKERS) {
            if (lowered.contains(marker)) return true;
        }
        return false;
    }

    private static final List<String> SECRET_MARKERS = List.of(
        "-----begin", "authorization:", "bearer ", "x-api-key", "api_key", "apikey",
        "access_token", "refresh_token", "client_secret", "private_key");
}
