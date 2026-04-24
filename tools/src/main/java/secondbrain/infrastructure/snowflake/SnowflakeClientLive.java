package secondbrain.infrastructure.snowflake;

import com.google.common.base.Preconditions;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang.StringUtils;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

import java.io.StringReader;
import java.security.PrivateKey;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.*;

@ApplicationScoped
public class SnowflakeClientLive implements SnowflakeClient {
    @Nullable
    private Connection connection;

    @Inject
    @ConfigProperty(name = "sb.snowflake.user")
    private Optional<String> username;

    @Inject
    @ConfigProperty(name = "sb.snowflake.url")
    private Optional<String> url;

    @Inject
    @ConfigProperty(name = "sb.snowflake.jwt.base64")
    private Optional<String> pem;

    public SnowflakeClientLive() {

    }

    public SnowflakeClientLive(final String username, final String pem, final String url) {
        this.username = Optional.of(username);
        this.url = Optional.of(url);
        this.pem = Optional.of(pem);
    }

    @PostConstruct
    public void openConnection() {
        if (connection != null || username.isEmpty() || url.isEmpty() || pem.isEmpty()) {
            return;
        }

        final PrivateKey privateKey = getPrivateKeyFromPEM(new String(Base64.getDecoder().decode(pem.get())));
        Properties properties = new Properties();
        properties.put("user", username.get());
        properties.put("authenticator", "SNOWFLAKE_JWT");
        properties.put("privateKey", privateKey);
        properties.put("JDBC_QUERY_RESULT_FORMAT", "json");
        properties.put("CLIENT_TELEMETRY_ENABLED", "false");

        final String jdbc = "jdbc:snowflake://" + url.get() + ".snowflakecomputing.com";

        connection = Try.of(() -> DriverManager.getConnection(jdbc, properties)).get();
    }

    @PreDestroy
    @SuppressWarnings("NullAway")
    public void closeConnection() {
        if (connection == null) {
            return;
        }

        Try.run(() -> this.connection.close());
        this.connection = null;
    }

    public PrivateKey getPrivateKeyFromPEM(final String pemString) {
        return Try.of(() -> new PEMParser(new StringReader(pemString)))
                .mapTry(PEMParser::readObject)
                .filter(o -> o instanceof PEMKeyPair)
                .map(o -> (PEMKeyPair) o)
                .mapTry(p -> new JcaPEMKeyConverter().getKeyPair(p).getPrivate())
                .mapFailure(API.Case(API.$(), ex -> new IllegalArgumentException("Failed to parse PEM string", ex)))
                .get();
    }

    @Override
    @SuppressWarnings("NullAway")
    public List<SnowflakeLicenseDetails> getLicenseDetails(final String id) {
        Preconditions.checkArgument(connection != null, "Connection must be established before querying");
        Preconditions.checkArgument(StringUtils.isNotBlank(id), "Id must be provided");

        return Try.of(() -> connection.prepareStatement(
                        "SELECT * FROM prod_model.integration.integration_account_usage_summary"
                                + " WHERE SFDC_ACCOUNT_SYSTEM_ID = ?"))
                .mapTry(statement -> {
                    statement.setString(1, id);
                    final ResultSet rs = statement.executeQuery();
                    final List<SnowflakeLicenseDetails> results = new ArrayList<>();
                    while (rs.next()) {
                        results.add(SnowflakeLicenseDetails.fromResultSet(rs));
                    }
                    return results;
                })
                .get();
    }
}
