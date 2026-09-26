import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.domain.HistoryServiceGrant;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.*;
import java.util.*;

public final class HistoryServiceGrantSmoke {
    static int checks;
    static final Instant NOW = Instant.parse("2026-09-25T00:00:00Z");
    static final TenantId TENANT = new TenantId("tenant-service");
    static final Set<Permission> PERMISSIONS = Set.of(Permission.ENTITY_READ, Permission.METRIC_READ, Permission.SOURCE_SYNC);
    static final Set<ResourceRef> REFS = Set.of(ResourceRef.source(TENANT, "zabbix-1"), new ResourceRef(TENANT, "entity", "00000000-0000-4000-8000-000000000001"), ResourceRef.metric(TENANT, "host.cpu.usage.user"));
    static IdentityGrant identity(boolean enabled, Set<Permission> permissions, ResourceScope scope) {
        return new IdentityGrant("https://issuer.invalid", "worker", 1, enabled, new Principal(new SubjectId("service"), TENANT, permissions, scope));
    }
    static HistoryServiceGrant grant(IdentityGrant identity, Instant until, long till, int window, int points, int rate) {
        return new HistoryServiceGrant(identity, "client", "zabbix-1", Set.of("20001"), NOW.minusSeconds(60), until, 100, till, window, points, rate);
    }
    static void denied(Runnable call) { try { call.run(); throw new AssertionError("Expected denial"); } catch (IllegalArgumentException expected) { checks++; } }
    public static void main(String[] args) {
        var id = identity(true, PERMISSIONS, ResourceScope.of(REFS)); var grant = grant(id, NOW.plusSeconds(3600), 10000, 3600, 500, 120);
        if (grant.authorize(id.issuer(), "client", "worker", NOW.plusSeconds(60), NOW, "zabbix-1", "20001", 100, 3699, 500) != id.principal()) throw new AssertionError(); checks++;
        denied(() -> grant.authorize("https://other.invalid", "client", "worker", NOW.plusSeconds(60), NOW, "zabbix-1", "20001", 100, 100, 500));
        denied(() -> grant.authorize(id.issuer(), "other-client", "worker", NOW.plusSeconds(60), NOW, "zabbix-1", "20001", 100, 100, 500));
        denied(() -> grant.authorize(id.issuer(), "client", "other-subject", NOW.plusSeconds(60), NOW, "zabbix-1", "20001", 100, 100, 500));
        denied(() -> grant.authorize(id.issuer(), "client", "worker", NOW, NOW, "zabbix-1", "20001", 100, 100, 500));
        denied(() -> grant.authorize(id.issuer(), "client", "worker", NOW.plusSeconds(60), NOW, "zabbix-2", "20001", 100, 100, 500));
        denied(() -> grant.authorize(id.issuer(), "client", "worker", NOW.plusSeconds(60), NOW, "zabbix-1", "20002", 100, 100, 500));
        for (long[] range : List.of(new long[]{99,100}, new long[]{10000,10001}, new long[]{101,100}, new long[]{100,3700}))
            denied(() -> grant.authorize(id.issuer(), "client", "worker", NOW.plusSeconds(60), NOW, "zabbix-1", "20001", range[0], range[1], 500));
        for (int points : new int[]{0,501}) denied(() -> grant.authorize(id.issuer(), "client", "worker", NOW.plusSeconds(60), NOW, "zabbix-1", "20001", 100,100,points));
        for (Instant when : List.of(NOW.minusSeconds(61), NOW.plusSeconds(3600))) denied(() -> grant.authorize(id.issuer(), "client", "worker", when.plusSeconds(60), when, "zabbix-1", "20001", 100,100,500));
        denied(() -> grant(identity(false, PERMISSIONS, ResourceScope.of(REFS)), NOW.plusSeconds(1), 100,1,1,1).authorize(id.issuer(),"client","worker",NOW.plusSeconds(1),NOW,"zabbix-1","20001",100,100,1));
        var future = new HistoryServiceGrant(id,"client","zabbix-1",Set.of("20001"),NOW.minusSeconds(60),NOW.plusSeconds(60),NOW.getEpochSecond()-10,NOW.getEpochSecond()+10,60,500,120);
        denied(() -> future.authorize(id.issuer(),"client","worker",NOW.plusSeconds(60),NOW,"zabbix-1","20001",NOW.getEpochSecond()-10,NOW.getEpochSecond(),500));
        for (ResourceScope scope : List.of(ResourceScope.tenantWide(), ResourceScope.of(Set.of(ResourceRef.source(TENANT,"zabbix-1"))), ResourceScope.of(Set.of(ResourceRef.source(TENANT,"*")))))
            denied(() -> grant(identity(true,PERMISSIONS,scope), NOW.plusSeconds(60),100,1,1,1));
        denied(() -> grant(identity(true,Set.of(Permission.ENTITY_READ),ResourceScope.of(REFS)),NOW.plusSeconds(60),100,1,1,1));
        denied(() -> grant(id,NOW.minusSeconds(60),100,1,1,1));
        denied(() -> grant(id,NOW.plus(Duration.ofDays(90)),100,1,1,1));
        denied(() -> grant(id,NOW.plusSeconds(60),100+31L*86400+1,1,1,1));
        for (int window : new int[]{0,3601}) denied(() -> grant(id,NOW.plusSeconds(60),100,window,1,1));
        for (int points : new int[]{0,501}) denied(() -> grant(id,NOW.plusSeconds(60),100,1,points,1));
        for (int rate : new int[]{0,121}) denied(() -> grant(id,NOW.plusSeconds(60),100,1,1,rate));
        System.out.println("HistoryServiceGrantSmoke: " + checks + " checks passed");
    }
}
