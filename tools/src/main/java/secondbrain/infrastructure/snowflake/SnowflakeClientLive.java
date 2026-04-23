package secondbrain.infrastructure.snowflake;

import io.vavr.control.Try;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import java.io.StringReader;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.Base64;
import java.util.Properties;

public class SnowflakeClientLive {

    public PrivateKey getPrivateKeyFromPEM(String pemString) throws Exception {
        PEMParser pemParser = new PEMParser(new StringReader(pemString));
        Object object = pemParser.readObject();

        // PEMKeyPair is used for PKCS#1 format
        JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
        if (object instanceof PEMKeyPair) {
            return converter.getKeyPair((PEMKeyPair) object).getPrivate();
        }
        throw new IllegalArgumentException("Not a valid PKCS#1 RSA private key");
    }

    public ResultSet getLicenseDetails(final String username, final String pem, final String url) {
        final PrivateKey privateKey = Try.of(() -> getPrivateKeyFromPEM(pem)).get();

        Properties properties = new Properties();
        properties.put("user", username);
        properties.put("authenticator", "SNOWFLAKE_JWT");
        properties.put("privateKey", privateKey);
        properties.put("JDBC_QUERY_RESULT_FORMAT", "json");

        return Try.withResources(() -> DriverManager.getConnection(url, properties))
                .of(connection -> {
                    // Create and execute a query
                    var statement = connection.createStatement();
                    return statement.executeQuery("SELECT * from prod_model.integration.integration_account_usage_summary");
                })
                .get();
    }
}
