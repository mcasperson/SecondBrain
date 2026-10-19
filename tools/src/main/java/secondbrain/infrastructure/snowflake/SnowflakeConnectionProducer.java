package secondbrain.infrastructure.snowflake;

import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.StringReader;
import java.security.PrivateKey;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.Optional;
import java.util.Properties;

/**
 * A factory that creates the Snowflake JDBC connection used by {@link SnowflakeClientLive}.
 * The connection is a dependent bean, so each lookup opens a new connection, and destroying the lookup closes it.
 * Tests replace this class with their own producer method that returns a test instance of SnowflakeConnection.
 */
@ApplicationScoped
public class SnowflakeConnectionProducer {
    @Inject
    @ConfigProperty(name = "sb.snowflake.user")
    private Optional<String> username;

    @Inject
    @ConfigProperty(name = "sb.snowflake.url")
    private Optional<String> url;

    @Inject
    @ConfigProperty(name = "sb.snowflake.jwt.base64")
    private Optional<String> pem;

    @Produces
    public SnowflakeConnection produceSnowflakeConnection() {
        if (username.isEmpty() || url.isEmpty() || pem.isEmpty()) {
            throw new IllegalStateException("Snowflake user, URL and JWT must be configured");
        }

        final PrivateKey privateKey = getPrivateKeyFromPEM(new String(Base64.getDecoder().decode(pem.get())));
        final Properties properties = new Properties();
        properties.put("user", username.get());
        properties.put("authenticator", "SNOWFLAKE_JWT");
        properties.put("privateKey", privateKey);
        properties.put("JDBC_QUERY_RESULT_FORMAT", "json");
        properties.put("CLIENT_TELEMETRY_ENABLED", "false");

        final String jdbc = "jdbc:snowflake://" + url.get() + ".snowflakecomputing.com";

        return new SnowflakeConnectionLive(Try.of(() -> DriverManager.getConnection(jdbc, properties)).get());
    }

    public void disposeSnowflakeConnection(@Disposes final SnowflakeConnection connection) {
        connection.close();
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
}
