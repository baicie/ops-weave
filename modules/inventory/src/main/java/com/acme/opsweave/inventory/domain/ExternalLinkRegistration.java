package com.acme.opsweave.inventory.domain;

import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A deterministic external-link registration: an explicit external object key bound onto one
 * existing entity. Nothing here is inferred from a name or an address; the whole key
 * (namespace, objectType, externalKey, generation) is asserted by an authorized operator so that a
 * workflow relation endpoint can resolve without guessing. The tenant and actor always come from
 * the trusted principal and are never part of the recorded content.
 */
public record ExternalLinkRegistration(UUID requestId, ExternalObjectKey key, EntityId targetId, String reason) {
    private static final Pattern NAMESPACE = Pattern.compile("^[a-z][a-z0-9._-]{0,95}$");
    private static final Pattern OBJECT_TYPE = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    public ExternalLinkRegistration {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(targetId, "targetId");
        if (reason != null && (reason.isBlank() || reason.length() > 256 || reason.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("Invalid external link reason");
        }
    }

    /**
     * Closed-set validation of the namespace and object type plus bounded key parts. The generation
     * completes the external object key and must already be resolved by the caller (it is the model
     * identity used by relation endpoint resolution), never guessed here.
     */
    public static ExternalLinkRegistration of(UUID requestId, TenantId tenant, String namespace, String objectType,
            String externalKey, String generation, EntityId targetId, String reason) {
        if (namespace == null || !NAMESPACE.matcher(namespace).matches()) throw new IllegalArgumentException("Invalid external link namespace");
        if (objectType == null || !OBJECT_TYPE.matcher(objectType).matches()) throw new IllegalArgumentException("Invalid external link object type");
        bounded(externalKey, 512, "external link key");
        bounded(generation, 128, "external link generation");
        return new ExternalLinkRegistration(requestId, new ExternalObjectKey(tenant, namespace, objectType, externalKey, generation), targetId, reason);
    }

    /** Idempotent replay compares the recorded content only; the registration time is not content. */
    public boolean sameContent(ExternalLinkRegistration other) {
        return other != null && key.equals(other.key) && targetId.equals(other.targetId) && Objects.equals(reason, other.reason);
    }

    private static void bounded(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid " + name);
        }
    }
}
