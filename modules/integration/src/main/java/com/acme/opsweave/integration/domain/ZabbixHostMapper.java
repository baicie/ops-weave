package com.acme.opsweave.integration.domain;

import com.acme.opsweave.inventory.domain.Entity;
import com.acme.opsweave.inventory.domain.EntityIds;
import com.acme.opsweave.inventory.domain.ExternalLink;
import com.acme.opsweave.inventory.domain.ExternalObjectKey;
import com.acme.opsweave.inventory.domain.Lifecycle;
import com.acme.opsweave.inventory.domain.Observation;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ZabbixHostMapper {
    public static final String GENERATION = "1";

    public MappedHost map(
        PipelineDefinition pipeline,
        TenantId tenantId,
        String sourceInstanceId,
        Map<String, Object> payload,
        Instant observedAt,
        Instant ingestedAt,
        String rawRecordRef
    ) {
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(sourceInstanceId, "sourceInstanceId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(ingestedAt, "ingestedAt");
        Objects.requireNonNull(rawRecordRef, "rawRecordRef");
        if (!"zabbix".equals(pipeline.sourceType()) || !"host".equals(pipeline.objectType())) {
            throw new IllegalArgumentException("Pipeline is not the Zabbix Host definition");
        }
        pipeline.executionOrder();
        String hostId = requiredText(payload.get("hostid"), "hostid");
        String technical = firstNonBlank(text(payload.get("host")), hostId);
        String nameField = pipeline.executionOrder().get(2).config().getOrDefault("displayNameField", "name");
        String name = firstNonBlank(text(payload.get(nameField)), technical);
        var version = PipelineVersion.of(pipeline);
        String status = enabledStatus(payload.get("status"));
        String ip = primaryIp(payload.get("interfaces"));
        ExternalObjectKey key = new ExternalObjectKey(tenantId, sourceInstanceId, "host", hostId, GENERATION);
        EntityId entityId = EntityIds.fromExternal(key);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("hostId", hostId);
        attributes.put("ip", ip);
        attributes.put("status", status);
        attributes.put("source", "zabbix");
        attributes.put("sourceInstanceId", sourceInstanceId);
        attributes.put("lastSeen", observedAt.toString());
        attributes.put("rawReference", rawRecordRef);
        attributes.put("pipelineId", pipeline.id());
        attributes.put("pipelineRevision", pipeline.revision());
        attributes.put("pipelineDigest", version.digest());
        Entity entity = new Entity(
            entityId,
            tenantId,
            "host",
            name,
            "enabled".equals(status) ? Lifecycle.ACTIVE : Lifecycle.INACTIVE,
            1,
            observedAt,
            attributes
        );
        var observedFields = new LinkedHashMap<>(attributes);
        observedFields.put("entityName", name); observedFields.put("entityType", "host"); observedFields.put("lifecycle", entity.lifecycle().name());
        Observation observation = new Observation(
            UUID.nameUUIDFromBytes((rawRecordRef + '|' + version.digest()).getBytes(StandardCharsets.UTF_8)).toString(),
            key,
            entityId,
            observedAt,
            ingestedAt,
            observedFields,
            rawRecordRef,
            pipeline.revision()
        );
        return new MappedHost(entity, observation, new ExternalLink(entityId, key));
    }

    public Optional<String> rejectReason(Map<String, Object> payload) {
        if (text(payload.get("hostid")).isBlank()) {
            return Optional.of("missing hostid");
        }
        return Optional.empty();
    }

    private static String enabledStatus(Object raw) {
        String value = text(raw);
        if ("0".equals(value) || "enabled".equalsIgnoreCase(value)) {
            return "enabled";
        }
        return "disabled";
    }

    private static String primaryIp(Object interfaces) {
        if (!(interfaces instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        String fallback = "";
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String ip = text(map.get("ip"));
            if (ip.isBlank()) {
                continue;
            }
            if (fallback.isEmpty()) {
                fallback = ip;
            }
            String main = text(map.get("main"));
            if ("1".equals(main) || "true".equalsIgnoreCase(main)) {
                return ip;
            }
        }
        return fallback;
    }

    private static String requiredText(Object value, String field) {
        String text = text(value);
        if (text.isBlank()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        return text;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstNonBlank(String left, String right) {
        return left.isBlank() ? right : left;
    }

    public record MappedHost(Entity entity, Observation observation, ExternalLink link) {
        /** Mode comes from the configured connector, never from an upstream host field. */
        public MappedHost withDataMode(String dataMode) {
            if (!Set.of("labeled-fixture", "zabbix-jsonrpc").contains(dataMode)) {
                throw new IllegalArgumentException("Unknown host data mode");
            }
            var attributes = new LinkedHashMap<>(entity.attributes());
            attributes.put("dataMode", dataMode);
            var fields = new LinkedHashMap<>(observation.fields()); fields.put("dataMode", dataMode);
            return new MappedHost(new Entity(entity.id(), entity.tenantId(), entity.entityType(), entity.name(),
                entity.lifecycle(), entity.version(), entity.lastSeen(), attributes),
                new Observation(observation.id(), observation.key(), observation.entityId(), observation.observedAt(),
                    observation.ingestedAt(), fields, observation.rawRecordRef(), observation.mappingRevision()), link);
        }
    }
}
