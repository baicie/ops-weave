import com.acme.opsweave.sharedkernel.*;
import com.acme.opsweave.inventory.domain.ExternalObjectKey;
import com.acme.opsweave.incident.domain.IncidentStatus;
import java.time.Instant;

public final class DomainSmoke {
    public static void main(String[] args) {
        var a = new ExternalObjectKey(new TenantId("a"), "zabbix-1", "host", "100", "1");
        var b = new ExternalObjectKey(new TenantId("b"), "zabbix-1", "host", "100", "1");
        var c = new ExternalObjectKey(new TenantId("a"), "zabbix-2", "host", "100", "1");
        var d = new ExternalObjectKey(new TenantId("a"), "zabbix-1", "host", "100", "2");
        require(!a.equals(b), "cross-tenant identity collision");
        require(!a.equals(c), "cross-source identity collision");
        require(!a.equals(d), "lifecycle identity collision");
        require(IncidentStatus.OPEN.canTransitionTo(IncidentStatus.INVESTIGATING), "valid transition");
        require(!IncidentStatus.OPEN.canTransitionTo(IncidentStatus.CLOSED), "invalid transition");
        require(!IncidentStatus.CLOSED.canTransitionTo(IncidentStatus.OPEN), "closed incident policy");
        try { new TimeRange(Instant.EPOCH, Instant.EPOCH); throw new AssertionError("accepted empty interval"); }
        catch (IllegalArgumentException expected) { /* expected */ }
        System.out.println("Java domain smoke: 7 checks passed");
    }
    private static void require(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }
}
