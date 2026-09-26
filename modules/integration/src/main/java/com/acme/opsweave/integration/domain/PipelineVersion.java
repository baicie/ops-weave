package com.acme.opsweave.integration.domain;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable deterministic host mapping. Change ENGINE when mapping semantics change. */
public record PipelineVersion(PipelineDefinition definition, String digest) {
    public static final String ENGINE = "zabbix-host-mapper-v1";

    public PipelineVersion {
        Objects.requireNonNull(definition, "definition");
        if (!"zabbix".equals(definition.sourceType()) || !"host".equals(definition.objectType())) {
            throw new IllegalArgumentException("Only Zabbix host pipelines are supported");
        }
        if (!digestOf(definition).equals(digest)) throw new IllegalArgumentException("Pipeline digest mismatch");
    }

    public static PipelineVersion of(PipelineDefinition definition) {
        return new PipelineVersion(definition, digestOf(definition));
    }

    public Ref ref() { return new Ref(definition.id(), definition.revision(), digest); }

    public record Ref(String id, int revision, String digest) {
        public Ref {
            if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || revision < 1
                || digest == null || !digest.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid pipeline version reference");
            }
        }
    }

    private static String digestOf(PipelineDefinition definition) {
        return digestOf(definition, ENGINE);
    }
    static String digestOf(PipelineDefinition definition, String engine) {
        try {
            var bytes = new ByteArrayOutputStream();
            var data = new DataOutputStream(bytes);
            data.writeUTF(engine);
            data.writeUTF(definition.id());
            data.writeInt(definition.revision());
            data.writeUTF(definition.sourceType());
            data.writeUTF(definition.objectType());
            data.writeUTF(definition.errorPolicy().wireName());
            for (PipelineNode node : definition.executionOrder()) {
                data.writeUTF(node.id());
                data.writeUTF(node.type().wireName());
                data.writeInt(node.config().size());
                for (var entry : new TreeMap<>(node.config()).entrySet()) {
                    data.writeUTF(entry.getKey());
                    data.writeUTF(entry.getValue());
                }
            }
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Pipeline digest unavailable");
        }
    }
}
