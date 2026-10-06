package com.acme.opsweave.integration.domain;

import java.time.Instant;
import java.util.*;

/** Metadata and immutable opaque versions. No plaintext secret belongs in this aggregate. */
public record SourceCredential(UUID id, String name, int revision, UUID versionId, int editVersion, String state, Instant createdAt, Instant updatedAt) {
    public static void checkName(String name) {
        if(name==null||name.isBlank()||!name.equals(name.trim())||name.length()>80||name.chars().anyMatch(c->Character.isISOControl(c)||c==0xfeff)||Character.isWhitespace(name.codePointAt(0))||Character.isSpaceChar(name.codePointAt(0))||Character.isWhitespace(name.codePointBefore(name.length()))||Character.isSpaceChar(name.codePointBefore(name.length())))throw new IllegalArgumentException("Invalid credential name");
    }
    public SourceCredential {
        Objects.requireNonNull(id); Objects.requireNonNull(versionId); Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
        checkName(name);
        if (revision < 1 || revision > 100 || editVersion < revision || editVersion > 1000 || state==null || !Set.of("ACTIVE","REVOKED").contains(state) || updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("Invalid credential metadata");
    }
    public record Pin(UUID credentialId, int revision, UUID versionId) {
        public Pin { Objects.requireNonNull(credentialId); Objects.requireNonNull(versionId); if(revision < 1 || revision > 100) throw new IllegalArgumentException("Invalid credential pin"); }
    }
    public Pin pin() { return new Pin(id, revision, versionId); }
    public record Envelope(String keyId, String nonce, String ciphertext) {
        public Envelope {
            if(keyId == null || !keyId.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}") || nonce == null || ciphertext == null || ciphertext.length() > 5500) throw new IllegalArgumentException("Invalid credential envelope");
            try { var n=Base64.getDecoder().decode(nonce); var c=Base64.getDecoder().decode(ciphertext); if(n.length != 12 || c.length < 17 || c.length > 4112 || !Base64.getEncoder().encodeToString(n).equals(nonce) || !Base64.getEncoder().encodeToString(c).equals(ciphertext)) throw new IllegalArgumentException(); } catch(IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid credential envelope"); }
        }
        @Override public String toString() { return "CredentialEnvelope[redacted]"; }
    }
    public record Version(UUID credentialId, int revision, UUID versionId, Envelope envelope, Instant createdAt) {
        public Version { new Pin(credentialId,revision,versionId); Objects.requireNonNull(envelope); Objects.requireNonNull(createdAt); }
        public Pin pin() { return new Pin(credentialId,revision,versionId); }
        @Override public String toString() { return "CredentialVersion["+credentialId+",revision="+revision+",redacted]"; }
    }
    public record Revocation(UUID credentialId, int revision, UUID requestId, Instant createdAt) {
        public Revocation { Objects.requireNonNull(credentialId); Objects.requireNonNull(requestId); Objects.requireNonNull(createdAt); if(revision < 1 || revision > 100) throw new IllegalArgumentException("Invalid credential revocation"); }
    }
    public record Receipt(UUID requestId, UUID credentialId, String operation, String commandDigest, String keyId, String secretDigest, SourceCredential credential) {
        public Receipt {
            Objects.requireNonNull(requestId); Objects.requireNonNull(credentialId); Objects.requireNonNull(credential); WorkflowDefinition.checkDigest(commandDigest);
            if(!credential.id().equals(credentialId) || !Set.of("CREATE","EDIT","REVOKE_VERSION").contains(operation) || operation.equals("CREATE")&&(keyId==null||!credentialId.equals(requestId)||credential.revision()!=1||credential.editVersion()!=1||!credential.state().equals("ACTIVE")||!credential.createdAt().equals(credential.updatedAt())||!credential.versionId().equals(requestId)) || !operation.equals("CREATE")&&credential.editVersion()<2 || operation.equals("REVOKE_VERSION")&&(keyId!=null||!credential.state().equals("ACTIVE")) || operation.equals("EDIT")&&keyId!=null&&(!credential.versionId().equals(requestId)||!credential.state().equals("ACTIVE")) || (keyId == null) != (secretDigest == null)
                || keyId != null && (!keyId.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}") || !secretDigest.matches("hmac-sha256:[a-f0-9]{64}"))) throw new IllegalArgumentException("Invalid credential receipt");
        }
        @Override public String toString() { return "CredentialReceipt["+requestId+",operation="+operation+",redacted]"; }
    }
}
