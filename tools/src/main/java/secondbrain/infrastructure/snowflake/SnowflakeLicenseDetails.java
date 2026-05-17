package secondbrain.infrastructure.snowflake;

import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.Objects;

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
    public Integer getTotalLicenseCount() {
        return Objects.requireNonNullElse(totalLicenseCount, 0);
    }

    public Integer getActiveLicenseCount() {
        return Objects.requireNonNullElse(activeLicenseCount, 0);
    }

    public LocalDate getLastRecordedAt() {
        return Objects.requireNonNullElse(lastRecordedAt, LocalDate.EPOCH);
    }

    public Integer getProjects() {
        return Objects.requireNonNullElse(projects, 0);
    }

    public Integer getProjects30dPrior() {
        return Objects.requireNonNullElse(projects30dPrior, 0);
    }

    public Integer getTenants() {
        return Objects.requireNonNullElse(tenants, 0);
    }

    public Integer getTenants30dPrior() {
        return Objects.requireNonNullElse(tenants30dPrior, 0);
    }

    public Integer getMachines() {
        return Objects.requireNonNullElse(machines, 0);
    }

    public Integer getMachines30dPrior() {
        return Objects.requireNonNullElse(machines30dPrior, 0);
    }

    public Integer getMonthlyActiveUsers() {
        return Objects.requireNonNullElse(monthlyActiveUsers, 0);
    }

    public Integer getMonthlyActiveUsers30dPrior() {
        return Objects.requireNonNullElse(monthlyActiveUsers30dPrior, 0);
    }

    public Integer getProjectsActive() {
        return Objects.requireNonNullElse(projectsActive, 0);
    }

    public Integer getProjectsActive30dPrior() {
        return Objects.requireNonNullElse(projectsActive30dPrior, 0);
    }

    public Integer getTenantsActive() {
        return Objects.requireNonNullElse(tenantsActive, 0);
    }

    public Integer getTenantsActive30dPrior() {
        return Objects.requireNonNullElse(tenantsActive30dPrior, 0);
    }

    public Integer getMachinesActive() {
        return Objects.requireNonNullElse(machinesActive, 0);
    }

    public Integer getMachinesActive30dPrior() {
        return Objects.requireNonNullElse(machinesActive30dPrior, 0);
    }

    public Double getDeploymentsPerDayCurrent() {
        return Objects.requireNonNullElse(deploymentsPerDayCurrent, 0.0);
    }

    public Double getDeploymentsPerDay30dPrior() {
        return Objects.requireNonNullElse(deploymentsPerDay30dPrior, 0.0);
    }

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

