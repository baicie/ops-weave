import com.acme.opsweave.integration.domain.ScanRunRetention;
import com.acme.opsweave.integration.domain.ScanRunRetention.Policy;
import com.acme.opsweave.integration.domain.SourceScanRunException;

/**
 * The published trace budget is a ceiling. An operator may only tighten it; an unusable or
 * inconsistent value is rejected instead of silently widening what the platform keeps.
 */
public final class ScanRunRetentionConfigSmoke {
    private static int checks = 0;

    public static void main(String[] args) {
        Policy defaults = Policy.of(null, null);
        require(defaults.maxRunsPerScope() == ScanRunRetention.DEFAULT_MAX_RUNS_PER_SCOPE
                && defaults.maxRunsPerTenant() == ScanRunRetention.DEFAULT_MAX_RUNS_PER_TENANT,
            "an unset budget is the published default");
        require(Policy.of(null, null).equals(Policy.defaults()), "Policy.of agrees with defaults()");

        Policy partial = Policy.of(50, null);
        require(partial.maxRunsPerScope() == 50
                && partial.maxRunsPerTenant() == ScanRunRetention.DEFAULT_MAX_RUNS_PER_TENANT,
            "a tightened scope budget keeps the published tenant ceiling");

        Policy tight = Policy.of(10, 20);
        require(tight.maxRunsPerScope() == 10 && tight.maxRunsPerTenant() == 20, "both budgets can be tightened");
        require(Policy.of(ScanRunRetention.DEFAULT_MAX_RUNS_PER_SCOPE,
                ScanRunRetention.DEFAULT_MAX_RUNS_PER_TENANT).equals(Policy.defaults()),
            "restating the published budgets is accepted");

        fails(() -> Policy.of(ScanRunRetention.DEFAULT_MAX_RUNS_PER_SCOPE + 1, null));
        fails(() -> Policy.of(null, ScanRunRetention.DEFAULT_MAX_RUNS_PER_TENANT + 1));
        fails(() -> Policy.of(0, 10));
        fails(() -> Policy.of(10, 0));
        fails(() -> Policy.of(-1, 10));
        fails(() -> Policy.of(20, 10));

        System.out.println("ScanRunRetentionConfigSmoke: " + checks + " checks passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
        checks++;
    }

    private static void fails(Runnable action) {
        try {
            action.run();
        } catch (SourceScanRunException expected) {
            require(expected.code() == SourceScanRunException.Code.INVALID_REQUEST,
                "an unusable budget is an invalid request");
            return;
        }
        throw new IllegalStateException("Expected an unusable budget to be rejected");
    }
}
