package secondbrain.infrastructure.snowflake;

import com.google.common.base.Preconditions;
import io.vavr.control.Try;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.apache.commons.lang.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.injection.Preferred;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
@Preferred
public class SnowflakeClientLive implements SnowflakeClient {
    @Nullable
    private SnowflakeConnection connection;

    @Inject
    @ConfigProperty(name = "sb.snowflake.user")
    private Optional<String> username;

    @Inject
    @ConfigProperty(name = "sb.snowflake.url")
    private Optional<String> url;

    @Inject
    @ConfigProperty(name = "sb.snowflake.jwt.base64")
    private Optional<String> pem;

    @Inject
    private Instance<SnowflakeConnection> connections;

    @PostConstruct
    public void openConnection() {
        if (connection != null || username.isEmpty() || url.isEmpty() || pem.isEmpty()) {
            return;
        }

        connection = connections.get();
    }

    @PreDestroy
    public void closeConnection() {
        if (connection == null) {
            return;
        }

        final SnowflakeConnection existing = connection;
        Try.run(() -> connections.destroy(existing));
        this.connection = null;
    }

    @Override
    @SuppressWarnings("NullAway")
    public List<SnowflakeLicenseDetails> getLicenseDetails(final String id) {
        Preconditions.checkArgument(connection != null, "Connection must be established before querying");
        Preconditions.checkArgument(StringUtils.isNotBlank(id), "Id must be provided");

        return connection.query(
                "SELECT * FROM prod_model.integration.integration_account_usage_summary"
                        + " WHERE SFDC_ACCOUNT_SYSTEM_ID = ?",
                id,
                SnowflakeLicenseDetails::fromRow);
    }

    @Override
    @SuppressWarnings("NullAway")
    public Optional<SnowflakeAdoptionIndexDetails> getAdoptionIndex(final String id) {
        Preconditions.checkArgument(connection != null, "Connection must be established before querying");
        Preconditions.checkArgument(StringUtils.isNotBlank(id), "Id must be provided");

        return connection.query(
                        "SELECT ACCOUNT_ID, ADOPTION_INDEX, ADOPTION_INDEX_STATUS, ADOPTION_INDEX_DATE"
                                + " FROM prod_marts.product_usage.mart_account_adoption_index"
                                + " WHERE ACCOUNT_ID = ?"
                                + " ORDER BY ADOPTION_INDEX_DATE DESC"
                                + " LIMIT 1",
                        id,
                        SnowflakeAdoptionIndexDetails::fromRow)
                .stream()
                .findFirst();
    }
}
