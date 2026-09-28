import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.telemetry.domain.*;
import java.nio.file.*;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;

public final class ZabbixLocalMetricsSmoke {
    static int checks;
    public static void main(String[] args) throws Exception {
        var memory = load("zabbix-memory-available");
        require(memory.normalize(Instant.EPOCH, new BigDecimal("75.5")).value().compareTo(new BigDecimal("0.755")) == 0,
            "available memory percent normalizes to a ratio");
        require(memory.unit().equals("1") && memory.metricType() == MetricType.GAUGE, "ratio is a gauge");
        for (String bad : List.of("-0.1", "100.1")) {
            try { memory.normalize(Instant.EPOCH, new BigDecimal(bad)); throw new AssertionError("range accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        var uptime = load("zabbix-system-uptime");
        require(uptime.metricType() == MetricType.GAUGE && uptime.unit().equals("s"), "uptime can decrease on reboot and is not a counter");
        require(uptime.normalize(Instant.EPOCH, new BigDecimal("42")).value().compareTo(new BigDecimal("42")) == 0, "uptime is seconds");
        var mapper = new ZabbixItemMapper(new MappingRegistry(List.of(memory, uptime)));
        require(mapper.rejectReason(Map.of("itemid","1","hostid","2","key_","system.uptime","value_type","3")).isEmpty(), "unsigned uptime accepted");
        require(mapper.rejectReason(Map.of("itemid","1","hostid","2","key_","system.uptime","value_type","4")).isPresent(), "text cannot masquerade as uptime");
        System.out.println("Zabbix local metrics smoke: " + checks + " checks passed");
    }
    static MappingDefinition load(String name) throws Exception {
        return MappingDocumentParser.parse(Files.readString(Path.of("extensions/mappings/"+name+".yaml")));
    }
    static void require(boolean value, String reason) { if (!value) throw new AssertionError(reason); checks++; }
}
