package secondbrain.infrastructure.snowflake;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import secondbrain.domain.test.TestConfigUtil;

import java.io.StringWriter;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the parts of the producer that do not need a real Snowflake account.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(SnowflakeConnectionProducer.class)
class SnowflakeConnectionProducerTest {
    @Inject
    private SnowflakeConnectionProducer producer;

    @Inject
    private Instance<SnowflakeConnection> connections;

    @ParameterizedTest
    @ValueSource(strings = {"sb.snowflake.user", "sb.snowflake.url", "sb.snowflake.jwt.base64"})
    void producingAConnectionWithoutEverySettingFails(final String missing) {
        final Map<String, String> config = new HashMap<>(Map.of(
                "sb.snowflake.user", "user",
                "sb.snowflake.url", "account",
                "sb.snowflake.jwt.base64", "cGVt"));
        config.remove(missing);
        TestConfigUtil.registerConfig(config);

        final IllegalStateException ex = assertThrows(IllegalStateException.class, connections::get);

        assertEquals("Snowflake user, URL and JWT must be configured", ex.getMessage());
    }

    @Test
    void privateKeysAreReadFromPem() throws Exception {
        TestConfigUtil.registerConfig(Map.of());
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        final KeyPair keyPair = generator.generateKeyPair();
        final StringWriter pem = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(pem)) {
            writer.writeObject(keyPair);
        }

        assertArrayEquals(keyPair.getPrivate().getEncoded(), producer.getPrivateKeyFromPEM(pem.toString()).getEncoded());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not a pem", "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----\n"})
    void invalidPemIsRejected(final String pem) {
        TestConfigUtil.registerConfig(Map.of());

        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> producer.getPrivateKeyFromPEM(pem));

        assertEquals("Failed to parse PEM string", ex.getMessage());
    }
}
