package secondbrain.infrastructure.snowflake;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.test.FakeSnowflakeConnection;
import secondbrain.domain.test.TestConfigUtil;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(SnowflakeClientLive.class)
class SnowflakeClientLiveUnitTest {
    private static final String LICENSE_SQL = "SELECT * FROM prod_model.integration.integration_account_usage_summary WHERE SFDC_ACCOUNT_SYSTEM_ID = ?";
    private static final String ADOPTION_SQL = "SELECT ACCOUNT_ID, ADOPTION_INDEX, ADOPTION_INDEX_STATUS, ADOPTION_INDEX_DATE"
            + " FROM prod_marts.product_usage.mart_account_adoption_index WHERE ACCOUNT_ID = ? ORDER BY ADOPTION_INDEX_DATE DESC LIMIT 1";

    private final FakeSnowflakeConnection connection = new FakeSnowflakeConnection();
    private final AtomicInteger connectionsCreated = new AtomicInteger();

    @Inject
    @Preferred
    private SnowflakeClientLive client;

    @Produces
    SnowflakeConnection produceSnowflakeConnection() {
        connectionsCreated.incrementAndGet();
        return connection;
    }

    void disposeSnowflakeConnection(@Disposes final SnowflakeConnection snowflakeConnection) {
        snowflakeConnection.close();
    }

    private static Map<String, String> fullConfig() {
        final Map<String, String> config = new HashMap<>();
        config.put("sb.snowflake.user", "user");
        config.put("sb.snowflake.url", "account");
        config.put("sb.snowflake.jwt.base64", "cGVt");
        return config;
    }

    private static void configure() {
        TestConfigUtil.registerConfig(fullConfig());
    }

    private static Map<String, Object> licenseRow() {
        final Map<String, Object> row = new HashMap<>();
        row.put("SFDC_ACCOUNT_SYSTEM_ID", "acc-1");
        row.put("TOTAL_LICENSE_COUNT", 50);
        row.put("ACTIVE_LICENSE_COUNT", 40);
        row.put("LAST_RECORDED_AT", LocalDate.of(2024, 5, 6));
        row.put("PROJECTS", 12);
        row.put("PROJECTS_30D_PRIOR", 10);
        row.put("TENANTS", 3);
        row.put("TENANTS_30D_PRIOR", 4);
        row.put("MACHINES", 30);
        row.put("MACHINES_30D_PRIOR", 20);
        row.put("MONTHLY_ACTIVE_USERS", 9);
        row.put("MONTHLY_ACTIVE_USERS_30D_PRIOR", 8);
        row.put("PROJECTS_ACTIVE", 7);
        row.put("PROJECTS_ACTIVE_30D_PRIOR", 6);
        row.put("TENANTS_ACTIVE", 5);
        row.put("TENANTS_ACTIVE_30D_PRIOR", 4);
        row.put("MACHINES_ACTIVE", 25);
        row.put("MACHINES_ACTIVE_30D_PRIOR", 24);
        row.put("DEPLOYMENTS_PER_DAY_CURRENT", 2.5);
        row.put("DEPLOYMENTS_PER_DAY_30D_PRIOR", 2.0);
        row.put("LICENSED_TARGETS", 100);
        row.put("LICENSED_TARGETS_30D_PRIOR", 80);
        return row;
    }

    @Test
    void getLicenseDetailsQueriesTheAccountAndMapsEveryColumn() {
        configure();
        connection.addRow(licenseRow());

        final List<SnowflakeLicenseDetails> results = client.getLicenseDetails("acc-1");

        assertEquals(List.of(new SnowflakeLicenseDetails("acc-1", 50, 40, LocalDate.of(2024, 5, 6), 12, 10, 3, 4, 30, 20, 9, 8,
                7, 6, 5, 4, 25, 24, 2.5, 2.0, 100, 80)), results);
        assertEquals(List.of(new FakeSnowflakeConnection.Query(LICENSE_SQL, "acc-1")), connection.queries);
    }

    @Test
    void getLicenseDetailsMapsNullColumnsToNullFields() {
        configure();
        connection.addRow(Map.of("SFDC_ACCOUNT_SYSTEM_ID", "acc-2"));

        final SnowflakeLicenseDetails details = client.getLicenseDetails("acc-2").getFirst();

        assertEquals("acc-2", details.sfdcAccountSystemId());
        assertNull(details.totalLicenseCount());
        assertNull(details.lastRecordedAt());
        assertEquals(LocalDate.EPOCH, details.getLastRecordedAt());
    }

    @Test
    void getLicenseDetailsReturnsEveryRowInOrder() {
        configure();
        connection.addRow(Map.of("SFDC_ACCOUNT_SYSTEM_ID", "first")).addRow(Map.of("SFDC_ACCOUNT_SYSTEM_ID", "second"));

        assertEquals(List.of("first", "second"),
                client.getLicenseDetails("acc").stream().map(SnowflakeLicenseDetails::sfdcAccountSystemId).toList());
    }

    @Test
    void getLicenseDetailsReturnsAnEmptyListWhenNoRowsMatch() {
        configure();

        assertEquals(List.of(), client.getLicenseDetails("missing"));
    }

    @Test
    void getAdoptionIndexReturnsTheFirstRow() {
        configure();
        connection.addRow(Map.of("ACCOUNT_ID", "acc-1", "ADOPTION_INDEX", 0.75, "ADOPTION_INDEX_STATUS", "Healthy",
                "ADOPTION_INDEX_DATE", LocalDate.of(2024, 6, 1)));
        connection.addRow(Map.of("ACCOUNT_ID", "older"));

        final Optional<SnowflakeAdoptionIndexDetails> result = client.getAdoptionIndex("acc-1");

        assertEquals(Optional.of(new SnowflakeAdoptionIndexDetails("acc-1", 0.75, "Healthy", LocalDate.of(2024, 6, 1))), result);
        assertEquals(List.of(new FakeSnowflakeConnection.Query(ADOPTION_SQL, "acc-1")), connection.queries);
    }

    @Test
    void getAdoptionIndexIsEmptyWhenNoRowsMatch() {
        configure();

        assertEquals(Optional.empty(), client.getAdoptionIndex("acc-1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sb.snowflake.user", "sb.snowflake.url", "sb.snowflake.jwt.base64"})
    void queriesFailWithoutAConnectionWhenAnySettingIsMissing(final String missing) {
        final Map<String, String> config = fullConfig();
        config.remove(missing);
        TestConfigUtil.registerConfig(config);

        final IllegalArgumentException license = assertThrows(IllegalArgumentException.class, () -> client.getLicenseDetails("acc"));
        final IllegalArgumentException adoption = assertThrows(IllegalArgumentException.class, () -> client.getAdoptionIndex("acc"));

        assertEquals("Connection must be established before querying", license.getMessage());
        assertEquals("Connection must be established before querying", adoption.getMessage());
        assertEquals(0, connectionsCreated.get());
        assertTrue(connection.queries.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void queriesRequireAnId(final String id) {
        configure();

        final IllegalArgumentException license = assertThrows(IllegalArgumentException.class, () -> client.getLicenseDetails(id));
        final IllegalArgumentException adoption = assertThrows(IllegalArgumentException.class, () -> client.getAdoptionIndex(id));

        assertEquals("Id must be provided", license.getMessage());
        assertEquals("Id must be provided", adoption.getMessage());
        assertTrue(connection.queries.isEmpty());
    }

    @Test
    void queryFailuresArePropagated() {
        configure();
        final IllegalStateException failure = new IllegalStateException("warehouse suspended");
        connection.failWith(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> client.getLicenseDetails("acc")));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> client.getAdoptionIndex("acc")));
    }

    @Test
    void openConnectionOnlyConnectsOnce() {
        configure();

        client.openConnection();
        client.openConnection();

        assertEquals(1, connectionsCreated.get());
    }

    @Test
    void closeConnectionReleasesTheConnectionOnce() {
        configure();
        client.openConnection();

        client.closeConnection();
        client.closeConnection();

        assertEquals(1, connection.closes());
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> client.getLicenseDetails("acc"));
        assertEquals("Connection must be established before querying", ex.getMessage());
    }

    @Test
    void openConnectionReconnectsAfterClosing() {
        configure();
        client.openConnection();
        client.closeConnection();

        client.openConnection();
        connection.addRow(Map.of("SFDC_ACCOUNT_SYSTEM_ID", "acc"));

        assertEquals(1, client.getLicenseDetails("acc").size());
        assertEquals(2, connectionsCreated.get());
    }
}
