package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.SourceCredential.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Private credential lifecycle. Network IO and public secret reads are not part of this service. */
public final class SourceCredentialService {
    public static final class Write implements AutoCloseable {
        public final UUID requestId; public final int expectedEditVersion; public final String name, state; private final char[] secret;
        public Write(UUID requestId,int expectedEditVersion,String name,String state,char[] secret) {
            this.requestId=Objects.requireNonNull(requestId); this.expectedEditVersion=expectedEditVersion; this.name=name; this.state=state;
            SourceCredential.checkName(name);
            if(expectedEditVersion<0 || expectedEditVersion>1000 || state==null || !Set.of("ACTIVE","REVOKED").contains(state)) throw new IllegalArgumentException("Invalid credential command");
            if(secret != null) { validateSecret(secret); if(!state.equals("ACTIVE")) throw new IllegalArgumentException("Invalid credential command"); }
            this.secret=secret==null?null:secret.clone();
        }
        public boolean changesSecret() { return secret!=null; }
        @Override public void close() { if(secret!=null) Arrays.fill(secret,'\0'); }
        @Override public String toString() { return "CredentialWrite["+requestId+",redacted]"; }
    }
    public record VersionView(Pin pin,Instant createdAt,boolean revoked) {}
    public record Page(List<SourceCredential> items,boolean truncated,boolean vaultAvailable,boolean canConfigure) {}
    private final WorkflowStore store; private final CredentialProtector protector; private final Clock clock;
    public SourceCredentialService(WorkflowStore store,CredentialProtector protector,Clock clock) { this.store=store;this.protector=protector;this.clock=clock; }
    public static void validateSecret(char[] value) { if(value.length<1 || value.length>4096) throw new IllegalArgumentException("Invalid credential secret"); for(char c:value) if(c<'!' || c>'~') throw new IllegalArgumentException("Invalid credential secret"); }
    private Instant now() { return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    private boolean allowed(Principal p,String id,Permission permission) { return new Authorizer().decide(p,new ResourceRef(p.tenantId(),"credential",id),permission).allowed(); }
    private void readAllowed(Principal p,String id) { if(!allowed(p,id,Permission.SOURCE_SYNC) && !allowed(p,id,Permission.SOURCE_CONFIGURE)) throw failure(SourceCredentialFailure.Code.FORBIDDEN); }
    private void writeAllowed(Principal p,String id) { if(!allowed(p,id,Permission.SOURCE_CONFIGURE)) throw failure(SourceCredentialFailure.Code.FORBIDDEN); }
    private static SourceCredentialFailure failure(SourceCredentialFailure.Code c) { return new SourceCredentialFailure(c); }
    private SourceCredential current(WorkflowStore.Session s,String owner,UUID id) {
        var c=s.credential(owner,id).orElseThrow(()->failure(SourceCredentialFailure.Code.NOT_FOUND));
        var versions=s.credentialVersions(owner,id); var revocations=s.credentialRevocations(owner,id);
        if(!c.id().equals(id) || versions.size()!=c.revision() || versions.stream().map(Version::revision).distinct().count()!=versions.size() || versions.stream().map(Version::versionId).distinct().count()!=versions.size()
            || versions.stream().anyMatch(v->!v.credentialId().equals(id) || v.revision()>c.revision() || v.createdAt().isBefore(c.createdAt()) || v.createdAt().isAfter(c.updatedAt()))
            || versions.stream().noneMatch(v->v.revision()==c.revision()&&v.versionId().equals(c.versionId()))
            || revocations.stream().map(Revocation::revision).distinct().count()!=revocations.size() || revocations.stream().anyMatch(r->!r.credentialId().equals(id) || r.revision()>c.revision() || r.createdAt().isBefore(c.createdAt()) || r.createdAt().isAfter(c.updatedAt()))) throw failure(SourceCredentialFailure.Code.UNAVAILABLE);
        return c;
    }
    public Page list(Principal p) { readAllowed(p,"*"); return store.transaction(p.tenantId(),s->{ var rows=s.credentials(p.subjectId().value()); var items=rows.stream().filter(c->allowed(p,c.id().toString(),Permission.SOURCE_SYNC)||allowed(p,c.id().toString(),Permission.SOURCE_CONFIGURE)).map(c->current(s,p.subjectId().value(),c.id())).sorted(Comparator.comparing(SourceCredential::updatedAt).reversed().thenComparing(c->c.id().toString())).toList(); return new Page(items.subList(0,Math.min(20,items.size())),items.size()>20,protector.available(),allowed(p,"*",Permission.SOURCE_CONFIGURE)); }); }
    public SourceCredential read(Principal p,UUID id) { readAllowed(p,id.toString()); return store.transaction(p.tenantId(),s->current(s,p.subjectId().value(),id)); }
    public List<VersionView> versions(Principal p,UUID id) { readAllowed(p,id.toString()); return store.transaction(p.tenantId(),s->{ var c=current(s,p.subjectId().value(),id); var revoked=s.credentialRevocations(p.subjectId().value(),id).stream().map(Revocation::revision).toList(); return s.credentialVersions(p.subjectId().value(),id).stream().map(v->new VersionView(v.pin(),v.createdAt(),c.state().equals("REVOKED")||revoked.contains(v.revision()))).toList(); }); }
    private Receipt checkedReceipt(WorkflowStore.Session s,String owner,UUID id,Receipt r) {
        if(!r.credentialId().equals(id))throw failure(SourceCredentialFailure.Code.NOT_FOUND);var latest=current(s,owner,id);var snapshot=r.credential();
        if(!snapshot.createdAt().equals(latest.createdAt())||snapshot.updatedAt().isAfter(latest.updatedAt())||snapshot.editVersion()>latest.editVersion()||snapshot.revision()>latest.revision()||s.credentialVersions(owner,id).stream().noneMatch(v->v.pin().equals(snapshot.pin())&&!v.createdAt().isAfter(snapshot.updatedAt())))throw failure(SourceCredentialFailure.Code.UNAVAILABLE);return r;
    }
    public Receipt receipt(Principal p,UUID id,UUID requestId) { readAllowed(p,id.toString()); return store.transaction(p.tenantId(),s->{var r=s.credentialReceipt(p.subjectId().value(),requestId).orElseThrow(()->failure(SourceCredentialFailure.Code.NOT_FOUND));return checkedReceipt(s,p.subjectId().value(),id,r);}); }
    public static List<String> parts(UUID id,Write c,boolean create) { return List.of("source-credential-write-v2",id.toString(),c.requestId.toString(),create?"CREATE":"EDIT",Integer.toString(c.expectedEditVersion),c.name,c.state,Boolean.toString(c.changesSecret())); }
    public Receipt write(Principal p,UUID id,Write command,boolean create) {
        writeAllowed(p,id.toString()); if(create && (!id.equals(command.requestId) || command.expectedEditVersion!=0 || !command.changesSecret() || !command.state.equals("ACTIVE")) || !create && command.expectedEditVersion==0) throw new IllegalArgumentException("Invalid credential command");
        var parts=parts(id,command,create); var publicDigest=WorkflowDefinition.hash(parts); var privateParts=new ArrayList<>(List.of(p.tenantId().value(),p.subjectId().value()));privateParts.addAll(parts);
        return store.transaction(p.tenantId(),s->{
            String owner=p.subjectId().value();var known=s.credentialReceipt(owner,command.requestId);
            if(known.isPresent()) { var r=known.get();if(!r.credentialId().equals(id) || !r.commandDigest().equals(publicDigest))throw failure(SourceCredentialFailure.Code.CONFLICT);
                if(command.changesSecret() && !same(r.secretDigest(),protector.authenticate(r.keyId(),privateParts,command.secret)))throw failure(SourceCredentialFailure.Code.CONFLICT);return checkedReceipt(s,owner,id,r); }
            if(s.credentialReceiptCount(owner)>=200)throw failure(SourceCredentialFailure.Code.CAPACITY);
            SourceCredential old;
            if(create) { if(s.credentialExists(id))throw failure(SourceCredentialFailure.Code.CONFLICT); if(s.credentials(owner).size()>=100)throw failure(SourceCredentialFailure.Code.CAPACITY);old=null; }
            else { old=current(s,owner,id);if(old.editVersion()!=command.expectedEditVersion || old.state().equals("REVOKED"))throw failure(SourceCredentialFailure.Code.CONFLICT);if(old.editVersion()>=1000)throw failure(SourceCredentialFailure.Code.CAPACITY); }
            int revision=old==null?1:old.revision()+(command.changesSecret()?1:0);if(revision>100)throw failure(SourceCredentialFailure.Code.CAPACITY);
            Instant at=now();if(old!=null&&at.isBefore(old.updatedAt()))throw failure(SourceCredentialFailure.Code.UNAVAILABLE);
            String keyId=null,secretDigest=null; Version version=null;
            UUID versionId=command.changesSecret()?command.requestId:old.versionId();
            if(command.changesSecret()) { keyId=protector.activeKeyId();secretDigest=protector.authenticate(keyId,privateParts,command.secret);var envelope=protector.seal(new CredentialProtector.Scope(p.tenantId(),owner,new Pin(id,revision,versionId)),command.secret);if(!keyId.equals(envelope.keyId()))throw failure(SourceCredentialFailure.Code.UNAVAILABLE);version=new Version(id,revision,versionId,envelope,at); }
            var next=new SourceCredential(id,command.name,revision,versionId,old==null?1:old.editVersion()+1,command.state,old==null?at:old.createdAt(),at);
            s.saveCredential(owner,next);if(version!=null)s.addCredentialVersion(owner,version);
            var receipt=new Receipt(command.requestId,id,create?"CREATE":"EDIT",publicDigest,keyId,secretDigest,next);s.addCredentialReceipt(owner,receipt);return receipt;
        });
    }
    private static boolean same(String a,String b) { return a!=null&&b!=null&&MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII),b.getBytes(StandardCharsets.US_ASCII)); }
    public static String revokeDigest(UUID id,UUID requestId,int expected,int revision) { return WorkflowDefinition.hash(List.of("source-credential-revoke-version-v2",id.toString(),requestId.toString(),Integer.toString(expected),Integer.toString(revision))); }
    public Receipt revoke(Principal p,UUID id,UUID requestId,int expected,int revision) {
        writeAllowed(p,id.toString());Objects.requireNonNull(requestId);if(expected<1||expected>1000||revision<1||revision>100)throw new IllegalArgumentException("Invalid credential revocation");String digest=revokeDigest(id,requestId,expected,revision);
        return store.transaction(p.tenantId(),s->{String owner=p.subjectId().value();var c=current(s,owner,id);var known=s.credentialReceipt(owner,requestId);if(known.isPresent()){if(!known.get().credentialId().equals(id)||!known.get().commandDigest().equals(digest))throw failure(SourceCredentialFailure.Code.CONFLICT);return checkedReceipt(s,owner,id,known.get());}
            if(c.editVersion()!=expected||revision>c.revision()||c.state().equals("REVOKED")||s.credentialRevocations(owner,id).stream().anyMatch(r->r.revision()==revision))throw failure(SourceCredentialFailure.Code.CONFLICT);if(c.editVersion()>=1000||s.credentialReceiptCount(owner)>=200)throw failure(SourceCredentialFailure.Code.CAPACITY);Instant at=now();if(at.isBefore(c.updatedAt()))throw failure(SourceCredentialFailure.Code.UNAVAILABLE);
            var next=new SourceCredential(id,c.name(),c.revision(),c.versionId(),c.editVersion()+1,c.state(),c.createdAt(),at);s.saveCredential(owner,next);s.addCredentialRevocation(owner,new Revocation(id,revision,requestId,at));var receipt=new Receipt(requestId,id,"REVOKE_VERSION",digest,null,null,next);s.addCredentialReceipt(owner,receipt);return receipt;});
    }
    private Version usable(WorkflowStore.Session s,Principal p,Pin pin) { var c=current(s,p.subjectId().value(),pin.credentialId());if(!c.state().equals("ACTIVE")||s.credentialRevocations(p.subjectId().value(),c.id()).stream().anyMatch(r->r.revision()==pin.revision()))throw failure(SourceCredentialFailure.Code.CONFLICT);return s.credentialVersions(p.subjectId().value(),c.id()).stream().filter(v->v.pin().equals(pin)).findFirst().orElseThrow(()->failure(SourceCredentialFailure.Code.NOT_FOUND)); }
    /** Metadata-only pin/key availability validation in the caller's transaction; never opens plaintext. */
    public void requirePin(WorkflowStore.Session session,Principal p,Pin pin){
        if(!allowed(p,pin.credentialId().toString(),Permission.SOURCE_SYNC))throw failure(SourceCredentialFailure.Code.FORBIDDEN);
        var version=usable(session,p,pin);if(!protector.canOpen(version.envelope().keyId()))throw failure(SourceCredentialFailure.Code.UNAVAILABLE);
    }
    /** For a trusted connector adapter only; bytes are cleared and revocation is rechecked after IO. */
    public <T> T withSecret(Principal p,Pin pin,Function<char[],T> readOnlyOperation) {
        if(!allowed(p,pin.credentialId().toString(),Permission.SOURCE_SYNC))throw failure(SourceCredentialFailure.Code.FORBIDDEN);
        var version=store.transaction(p.tenantId(),s->usable(s,p,pin));char[] secret=protector.open(new CredentialProtector.Scope(p.tenantId(),p.subjectId().value(),pin),version.envelope());
        try { validateSecret(secret);T result=readOnlyOperation.apply(secret);store.transaction(p.tenantId(),s->{if(!usable(s,p,pin).equals(version))throw failure(SourceCredentialFailure.Code.CONFLICT);return null;});return result; } finally { Arrays.fill(secret,'\0'); }
    }
}
