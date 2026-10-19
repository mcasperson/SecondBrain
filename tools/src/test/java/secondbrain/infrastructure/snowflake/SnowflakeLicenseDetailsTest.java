package secondbrain.infrastructure.snowflake;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SnowflakeLicenseDetails is a record with no dependencies, so it is created directly.
 */
class SnowflakeLicenseDetailsTest {
    private static final SnowflakeLicenseDetails EMPTY = new SnowflakeLicenseDetails("acc", null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null, null);

    /**
     * Every current value is "current" and every prior value is "prior".
     */
    private static SnowflakeLicenseDetails uniform(final Integer current, final Integer prior, final Double currentDeployments, final Double priorDeployments) {
        return new SnowflakeLicenseDetails("acc", 1, 2, LocalDate.of(2024, 1, 2), current, prior, current, prior, current, prior,
                current, prior, current, prior, current, prior, current, prior, currentDeployments, priorDeployments, current, prior);
    }

    @Test
    void nullValuesDefaultToZeroAndTheEpoch() {
        assertEquals(0, EMPTY.getTotalLicenseCount());
        assertEquals(0, EMPTY.getActiveLicenseCount());
        assertEquals(LocalDate.EPOCH, EMPTY.getLastRecordedAt());
        assertEquals(0, EMPTY.getProjects());
        assertEquals(0, EMPTY.getProjects30dPrior());
        assertEquals(0, EMPTY.getTenants());
        assertEquals(0, EMPTY.getTenants30dPrior());
        assertEquals(0, EMPTY.getMachines());
        assertEquals(0, EMPTY.getMachines30dPrior());
        assertEquals(0, EMPTY.getMonthlyActiveUsers());
        assertEquals(0, EMPTY.getMonthlyActiveUsers30dPrior());
        assertEquals(0, EMPTY.getProjectsActive());
        assertEquals(0, EMPTY.getProjectsActive30dPrior());
        assertEquals(0, EMPTY.getTenantsActive());
        assertEquals(0, EMPTY.getTenantsActive30dPrior());
        assertEquals(0, EMPTY.getMachinesActive());
        assertEquals(0, EMPTY.getMachinesActive30dPrior());
        assertEquals(0.0, EMPTY.getDeploymentsPerDayCurrent());
        assertEquals(0.0, EMPTY.getDeploymentsPerDay30dPrior());
        assertEquals(0, EMPTY.getLicensedTargets());
        assertEquals(0, EMPTY.getLicensedTargets30dPrior());
    }

    @Test
    void populatedValuesAreReturnedUnchanged() {
        final SnowflakeLicenseDetails details = new SnowflakeLicenseDetails("acc", 1, 2, LocalDate.of(2024, 1, 2), 3, 4, 5, 6, 7, 8,
                9, 10, 11, 12, 13, 14, 15, 16, 17.5, 18.5, 19, 20);

        assertEquals(1, details.getTotalLicenseCount());
        assertEquals(2, details.getActiveLicenseCount());
        assertEquals(LocalDate.of(2024, 1, 2), details.getLastRecordedAt());
        assertEquals(3, details.getProjects());
        assertEquals(4, details.getProjects30dPrior());
        assertEquals(5, details.getTenants());
        assertEquals(6, details.getTenants30dPrior());
        assertEquals(7, details.getMachines());
        assertEquals(8, details.getMachines30dPrior());
        assertEquals(9, details.getMonthlyActiveUsers());
        assertEquals(10, details.getMonthlyActiveUsers30dPrior());
        assertEquals(11, details.getProjectsActive());
        assertEquals(12, details.getProjectsActive30dPrior());
        assertEquals(13, details.getTenantsActive());
        assertEquals(14, details.getTenantsActive30dPrior());
        assertEquals(15, details.getMachinesActive());
        assertEquals(16, details.getMachinesActive30dPrior());
        assertEquals(17.5, details.getDeploymentsPerDayCurrent());
        assertEquals(18.5, details.getDeploymentsPerDay30dPrior());
        assertEquals(19, details.getLicensedTargets());
        assertEquals(20, details.getLicensedTargets30dPrior());
    }

    @ParameterizedTest(name = "{0} -> {1}: {4}%")
    @CsvSource({
            // A zero prior value can not be divided by, so the change is zero
            "5, 0, 5.0, 0.0, 0",
            "0, 0, 0.0, 0.0, 0",
            "12, 10, 12.0, 10.0, 20",
            "5, 10, 5.0, 10.0, -50",
            "0, 4, 0.0, 4.0, -100",
            // Fractions are truncated towards zero rather than rounded
            "2, 3, 2.0, 3.0, -33",
            "4, 3, 4.0, 3.0, 33",
            "299, 100, 299.0, 100.0, 199"
    })
    void percentChangesCompareTheCurrentAndPriorValues(final int current, final int prior, final double currentDeployments,
                                                       final double priorDeployments, final int expected) {
        final SnowflakeLicenseDetails details = uniform(current, prior, currentDeployments, priorDeployments);

        assertEquals(expected, details.getProjectsPercentChange());
        assertEquals(expected, details.getTenantsPercentChange());
        assertEquals(expected, details.getMachinesPercentChange());
        assertEquals(expected, details.getMonthlyActiveUsersPercentChange());
        assertEquals(expected, details.getProjectsActivePercentChange());
        assertEquals(expected, details.getTenantsActivePercentChange());
        assertEquals(expected, details.getMachinesActivePercentChange());
        assertEquals(expected, details.getDeploymentsPerDayPercentChange());
        assertEquals(expected, details.getLicensedTargetsPercentChange());
    }

    @Test
    void deploymentPercentChangeUsesFractionalValues() {
        assertEquals(25, uniform(1, 1, 2.5, 2.0).getDeploymentsPerDayPercentChange());
        assertEquals(0, uniform(1, 1, 0.5, 0.0).getDeploymentsPerDayPercentChange());
    }

    @Test
    void percentChangesAreZeroWhenValuesAreMissing() {
        assertEquals(0, EMPTY.getProjectsPercentChange());
        assertEquals(0, EMPTY.getTenantsPercentChange());
        assertEquals(0, EMPTY.getMachinesPercentChange());
        assertEquals(0, EMPTY.getMonthlyActiveUsersPercentChange());
        assertEquals(0, EMPTY.getProjectsActivePercentChange());
        assertEquals(0, EMPTY.getTenantsActivePercentChange());
        assertEquals(0, EMPTY.getMachinesActivePercentChange());
        assertEquals(0, EMPTY.getDeploymentsPerDayPercentChange());
        assertEquals(0, EMPTY.getLicensedTargetsPercentChange());
    }

    @Test
    void fromRowReadsEachColumnByLabel() {
        final SnowflakeRow row = new SnowflakeRow() {
            @Override
            public String getString(final String column) {
                return "id:" + column;
            }

            @Override
            public <T> T getObject(final String column, final Class<T> type) {
                // Encode the column name length so each field proves which column it came from
                return type == Integer.class ? type.cast(column.length()) : type.cast((double) column.length());
            }

            @Override
            public @Nullable LocalDate getDate(final String column) {
                return column.equals("LAST_RECORDED_AT") ? LocalDate.of(2023, 3, 4) : null;
            }
        };

        final SnowflakeLicenseDetails details = SnowflakeLicenseDetails.fromRow(row);

        assertEquals(new SnowflakeLicenseDetails("id:SFDC_ACCOUNT_SYSTEM_ID",
                "TOTAL_LICENSE_COUNT".length(), "ACTIVE_LICENSE_COUNT".length(), LocalDate.of(2023, 3, 4),
                "PROJECTS".length(), "PROJECTS_30D_PRIOR".length(), "TENANTS".length(), "TENANTS_30D_PRIOR".length(),
                "MACHINES".length(), "MACHINES_30D_PRIOR".length(), "MONTHLY_ACTIVE_USERS".length(), "MONTHLY_ACTIVE_USERS_30D_PRIOR".length(),
                "PROJECTS_ACTIVE".length(), "PROJECTS_ACTIVE_30D_PRIOR".length(), "TENANTS_ACTIVE".length(), "TENANTS_ACTIVE_30D_PRIOR".length(),
                "MACHINES_ACTIVE".length(), "MACHINES_ACTIVE_30D_PRIOR".length(), (double) "DEPLOYMENTS_PER_DAY_CURRENT".length(),
                (double) "DEPLOYMENTS_PER_DAY_30D_PRIOR".length(), "LICENSED_TARGETS".length(), "LICENSED_TARGETS_30D_PRIOR".length()), details);
    }
}
