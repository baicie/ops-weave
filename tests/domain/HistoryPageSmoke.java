import com.acme.opsweave.integration.domain.HistoryCursor;
import com.acme.opsweave.integration.domain.HistoryPage;
import com.acme.opsweave.integration.domain.HistoryReadException;
import com.acme.opsweave.integration.domain.HistoryWindow;
import com.acme.opsweave.telemetry.domain.MetricPoint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class HistoryPageSmoke {
    private static int checks;
    public static void main(String[] args) {
        List<MetricPoint> sameSecond = List.of(point(10, 1), point(10, 2), point(10, 3));
        HistoryPage first = HistoryPage.select(new HistoryWindow(10, 20, null, 2), sameSecond);
        check(first.points().size() == 2 && !first.windowComplete());
        check(first.nextCursor().equals(new HistoryCursor(10, 2)));
        HistoryPage next = HistoryPage.select(new HistoryWindow(10, 20, first.nextCursor(), 2), sameSecond);
        check(next.points().equals(List.of(point(10, 3))) && next.windowComplete());
        check(next.nextCursor().equals(new HistoryCursor(20, 999_999_999)));
        check(HistoryPage.select(new HistoryWindow(10, 20, null, 1), List.of()).windowComplete());
        List<MetricPoint> saturated = new ArrayList<>();
        saturated.add(point(10, 1));
        for (int i = 0; i < 500; i++) saturated.add(point(11, i));
        HistoryPage safe = HistoryPage.select(new HistoryWindow(10, 20, null, 500), saturated);
        check(safe.points().equals(List.of(point(10, 1))));
        check(!safe.windowComplete() && safe.nextCursor().equals(new HistoryCursor(10, 999_999_999)));
        List<MetricPoint> dense = new ArrayList<>();
        for (int i = 0; i < 501; i++) dense.add(point(10, i));
        reject(HistoryReadException.Code.HISTORY_SECOND_LIMIT, dense);
        reject(HistoryReadException.Code.INVALID_SOURCE_RESPONSE, List.of(point(10, 2), point(10, 1)));
        reject(HistoryReadException.Code.INVALID_SOURCE_RESPONSE, List.of(point(10, 1), point(10, 1)));
        reject(HistoryReadException.Code.INVALID_SOURCE_RESPONSE, List.of(point(9, 1)));
        reject(HistoryReadException.Code.INVALID_SOURCE_RESPONSE, List.of(point(21, 1)));
        dense.add(point(11, 0));
        reject(HistoryReadException.Code.INVALID_SOURCE_RESPONSE, dense);
        System.out.println("HistoryPageSmoke: " + checks + " checks passed");
    }

    private static MetricPoint point(long clock, int ns) {
        return new MetricPoint(Instant.ofEpochSecond(clock, ns), BigDecimal.ONE);
    }
    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("History page check " + (checks + 1));
        checks++;
    }
    private static void reject(HistoryReadException.Code code, List<MetricPoint> points) {
        try { HistoryPage.select(new HistoryWindow(10, 20, null, 500), points); }
        catch (HistoryReadException expected) { check(expected.code() == code); return; }
        throw new AssertionError("Expected " + code);
    }
}
