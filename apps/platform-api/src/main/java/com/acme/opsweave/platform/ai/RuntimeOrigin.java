package com.acme.opsweave.platform.ai;

import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Additional process attestation for result submission. It never grants the calling user access. */
@Component
public final class RuntimeOrigin {
    private final byte[] key;
    private final String skillDigest;
    private final InsightSubmission.ModelRef model;
    public RuntimeOrigin(OpsweaveProperties properties,
            @Value("${opsweave.ai.runtime-key:}") String key,
            @Value("${opsweave.ai.model-provider:mock}") String provider,
            @Value("${opsweave.ai.model-name:}") String modelName) {
        if (!key.isEmpty() && (!Set.of("dev", "oidc").contains(properties.auth().mode()) || !properties.auth().bindLoopbackOnly()
            || key.length() < 32 || key.length() > 4096 || !key.chars().allMatch(c -> c >= 33 && c <= 126))) throw new IllegalStateException("Runtime result attestation requires explicit loopback configuration");
        this.key = key.getBytes(StandardCharsets.UTF_8);
        model = switch (provider) {
            case "mock" -> new InsightSubmission.ModelRef("mock-deterministic", "mock-current-v1");
            case "rig-openai" -> new InsightSubmission.ModelRef("rig-openai", modelName);
            default -> throw new IllegalStateException("Unsupported model provider policy");
        };
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String file : List.of("skill.json", "prompt.md", "output.schema.json")) {
                try (var stream = RuntimeOrigin.class.getResourceAsStream("/skills/incident-diagnosis-current/" + file)) {
                    if (stream == null) throw new IllegalStateException("Pinned current knowledge Skill missing");
                    byte[] data = stream.readNBytes(65537); if (data.length > 65536) throw new IllegalStateException("Skill exceeds file budget");
                    digest.update(ByteBuffer.allocate(8).putLong(data.length).array()); digest.update(data);
                }
            }
            skillDigest = "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException error) { throw new IllegalStateException("Cannot load pinned Skill"); }
    }
    public void require(HttpServletRequest request) {
        if (key.length == 0) throw new ToolFailure(ToolFailure.Code.UNAVAILABLE);
        var headers = Collections.list(request.getHeaders("X-OpsWeave-Runtime-Key"));
        try {
            if (!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress() || headers.size() != 1
                || !MessageDigest.isEqual(key, headers.getFirst().getBytes(StandardCharsets.UTF_8))) throw new ToolFailure(ToolFailure.Code.FORBIDDEN);
        } catch (java.net.UnknownHostException invalid) { throw new ToolFailure(ToolFailure.Code.FORBIDDEN); }
    }
    public void validate(InsightSubmission input) {
        if (!input.skill().digest().equals(skillDigest) || !input.model().equals(model)) throw new ToolFailure(ToolFailure.Code.FORBIDDEN);
    }
}
