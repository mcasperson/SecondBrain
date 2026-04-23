package secondbrain.infrastructure.snowflake;

import com.google.common.base.Preconditions;
import io.vavr.control.Try;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.jspecify.annotations.Nullable;

import java.io.StringReader;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.Base64;
import java.util.Properties;

public class SnowflakeClientLive {

    @Nullable
    private Connection connection;

    public void openConnection(final String username, final String pem, final String url) {
        if (connection != null) {
            return;
        }

        final PrivateKey privateKey = Try.of(() -> getPrivateKeyFromPEM(pem)).get();

        Properties properties = new Properties();
        properties.put("user", username);
        properties.put("authenticator", "SNOWFLAKE_JWT");
        properties.put("privateKey", privateKey);
        properties.put("JDBC_QUERY_RESULT_FORMAT", "json");
        connection = Try.of(() -> DriverManager.getConnection(url, properties)).get();
    }

    @SuppressWarnings("NullAway")
    public void closeConnection() {
        if (connection == null) {
            return;
        }

        Try.run(() -> this.connection.close());
        this.connection = null;
    }

    public PrivateKey getPrivateKeyFromPEM(String pemString) throws Exception {
        PEMParser pemParser = new PEMParser(new StringReader(pemString));
        Object object = pemParser.readObject();

        // PEMKeyPair is used for PKCS#1 format
        JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
        if (object instanceof PEMKeyPair pemKeyPair) {
            return converter.getKeyPair(pemKeyPair).getPrivate();
        }
        throw new IllegalArgumentException("Not a valid PKCS#1 RSA private key");
    }

    @SuppressWarnings("NullAway")
    public ResultSet getLicenseDetails() {
        Preconditions.checkArgument(connection != null, "Connection must be established before querying");

        return Try.of(() -> connection.createStatement())
                .mapTry(statement -> statement.executeQuery("SELECT * from prod_model.integration.integration_account_usage_summary"))
                .get();

    }
}
