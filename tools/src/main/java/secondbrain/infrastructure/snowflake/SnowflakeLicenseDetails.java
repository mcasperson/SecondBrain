package secondbrain.infrastructure.snowflake;

import org.jspecify.annotations.Nullable;

import java.time.LocalDate;

public record SnowflakeLicenseDetails(
        String sfdcAccountSystemId,
        @Nullable Integer totalLicenseCount,
        @Nullable Integer activeLicenseCount,
        @Nullable LocalDate lastRecordedAt,
        @Nullable Integer projects,
        @Nullable Integer projects30dPrior,
        @Nullable Integer tenants,
        @Nullable Integer tenants30dPrior,
        @Nullable Integer machines,
        @Nullable Integer machines30dPrior,
        @Nullable Integer monthlyActiveUsers,
        @Nullable Integer monthlyActiveUsers30dPrior,
        @Nullable Integer projectsActive,
        @Nullable Integer projectsActive30dPrior,
        @Nullable Integer tenantsActive,
        @Nullable Integer tenantsActive30dPrior,
        @Nullable Integer machinesActive,
        @Nullable Integer machinesActive30dPrior,
        @Nullable Double deploymentsPerDayCurrent,
        @Nullable Double deploymentsPerDay30dPrior
) {
    public static SnowflakeLicenseDetails fromResultSet(final java.sql.ResultSet rs) throws java.sql.SQLException {
        final java.sql.Date lastRecordedAtDate = rs.getDate("LAST_RECORDED_AT");
        return new SnowflakeLicenseDetails(
                rs.getString("SFDC_ACCOUNT_SYSTEM_ID"),
                rs.getObject("TOTAL_LICENSE_COUNT", Integer.class),
                rs.getObject("ACTIVE_LICENSE_COUNT", Integer.class),
                lastRecordedAtDate != null ? lastRecordedAtDate.toLocalDate() : null,
                rs.getObject("PROJECTS", Integer.class),
                rs.getObject("PROJECTS_30D_PRIOR", Integer.class),
                rs.getObject("TENANTS", Integer.class),
                rs.getObject("TENANTS_30D_PRIOR", Integer.class),
                rs.getObject("MACHINES", Integer.class),
                rs.getObject("MACHINES_30D_PRIOR", Integer.class),
                rs.getObject("MONTHLY_ACTIVE_USERS", Integer.class),
                rs.getObject("MONTHLY_ACTIVE_USERS_30D_PRIOR", Integer.class),
                rs.getObject("PROJECTS_ACTIVE", Integer.class),
                rs.getObject("PROJECTS_ACTIVE_30D_PRIOR", Integer.class),
                rs.getObject("TENANTS_ACTIVE", Integer.class),
                rs.getObject("TENANTS_ACTIVE_30D_PRIOR", Integer.class),
                rs.getObject("MACHINES_ACTIVE", Integer.class),
                rs.getObject("MACHINES_ACTIVE_30D_PRIOR", Integer.class),
                rs.getObject("DEPLOYMENTS_PER_DAY_CURRENT", Double.class),
                rs.getObject("DEPLOYMENTS_PER_DAY_30D_PRIOR", Double.class)
        );
    }
}

