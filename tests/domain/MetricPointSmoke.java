import com.acme.opsweave.integration.domain.HistoryCursor;
import com.acme.opsweave.integration.domain.HistoryWindow;
import com.acme.opsweave.integration.domain.MappingDocumentParser;
import com.acme.opsweave.telemetry.domain.MetricPoint;
import java.math.BigDecimal;
import java.time.Instant;

public final class MetricPointSmoke {
    public static void main(String[] args) throws Exception {
        Instant time = Instant.ofEpochSecond(100, 123);
        if (MetricPoint.normalize(time, new BigDecimal("25"), "multiply:0.01").value().compareTo(new BigDecimal("0.25")) != 0) {
            throw new AssertionError("Percent conversion");
        }
        var exact = MetricPoint.normalize(time, new BigDecimal("18446744073709551615"), "identity");
        if (!exact.value().toPlainString().equals("18446744073709551615") || exact.timestamp().getNano() != 123) {
            throw new AssertionError("Precision");
        }
        reject(() -> new HistoryCursor(100, -1));
        reject(() -> new HistoryCursor(100, 1_000_000_000));
        reject(() -> new HistoryWindow(0, 3600, null, 100));
        reject(() -> new HistoryWindow(0, 100, new HistoryCursor(101, 0), 100));
        reject(() -> new HistoryWindow(0, 100, null, 501));
        reject(() -> MetricPoint.normalize(time, BigDecimal.ONE, "script:evil"));
        reject(() -> new MetricPoint(time, new BigDecimal("1e309")));
        if (new HistoryWindow(10, 20, new HistoryCursor(10, 999_999_999), 1).fetchFrom() != 11) {
            throw new AssertionError("Completed second advances");
        }
        String yaml = java.nio.file.Files.readString(java.nio.file.Path.of("extensions/mappings/zabbix-cpu-user.yaml"));
        var mapping = MappingDocumentParser.parse(yaml);
        reject(() -> mapping.normalize(time, new BigDecimal("101")));
        reject(() -> mapping.normalize(time, new BigDecimal("-1")));
        reject(() -> MappingDocumentParser.parse(yaml.replace("min: 0", "min: 2")));
        reject(() -> MappingDocumentParser.parse(yaml.replace("max: 1", "max: NaN")));
        if (mapping.normalize(time, new BigDecimal("100")).value().compareTo(BigDecimal.ONE) != 0) {
            throw new AssertionError("Configured inclusive range");
        }
        var identity = MappingDocumentParser.parse(yaml.replace("operation: multiply", "operation: identity").replace("  factor: 0.01\n", ""));
        if (identity.normalize(time, new BigDecimal("0.5")).value().compareTo(new BigDecimal("0.5")) != 0) {
            throw new AssertionError("Configured identity transform");
        }
        System.out.println("MetricPointSmoke: 16 checks passed");
    }

    private static void reject(Runnable operation) {
        try { operation.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejection");
    }
}
