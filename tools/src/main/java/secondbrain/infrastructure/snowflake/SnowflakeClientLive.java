package secondbrain.infrastructure.snowflake;

import com.google.common.base.Preconditions;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang.StringUtils;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.jspecify.annotations.Nullable;

import java.io.StringReader;
import java.security.PrivateKey;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.Properties;

@ApplicationScoped
public class SnowflakeClientLive {
    @Nullable
    private Connection connection;

    @PostConstruct
    public void openConnection(final String username, final String pem, final String url) {
        if (connection != null) {
            return;
        }

        final PrivateKey privateKey = getPrivateKeyFromPEM(pem);


        Properties properties = new Properties();
        properties.put("user", username);
        properties.put("authenticator", "SNOWFLAKE_JWT");
        properties.put("privateKey", privateKey);
        properties.put("JDBC_QUERY_RESULT_FORMAT", "json");
        properties.put("CLIENT_TELEMETRY_ENABLED", "false");
        connection = Try.of(() -> DriverManager.getConnection(url, properties)).get();
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

    @SuppressWarnings("NullAway")
    public ResultSet getLicenseDetails(final String id) {
        Preconditions.checkArgument(connection != null, "Connection must be established before querying");
        Preconditions.checkArgument(StringUtils.isNotBlank(id), "Id must be provided");

        return Try.of(() -> connection.createStatement())
                .mapTry(statement -> statement.executeQuery("SELECT * from prod_model.integration.integration_account_usage_summary where SFDC_ACCOUNT_SYSTEM_ID = '" + id + "'"))
                .get();

    }
}
