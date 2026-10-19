package secondbrain.domain.encryption;

import io.smallrye.common.annotation.Identifier;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jasypt.exceptions.EncryptionOperationNotPossibleException;
import org.jasypt.util.text.StrongTextEncryptor;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import secondbrain.domain.exceptionhandling.LoggingExceptionHandler;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.test.TestConfigUtil;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(JasyptEncryptor.class)
@AddBeanClasses(Loggers.class)
@AddBeanClasses(LoggingExceptionHandler.class)
class JasyptEncryptorTest {
    private static final String PASSWORD = "test-password";

    @Inject
    @Identifier("Jasypt")
    private Encryptor encryptor;

    @BeforeAll
    static void registerConfig() {
        TestConfigUtil.registerConfig(Map.of("sb.encryption.password", PASSWORD));
    }

    private static StrongTextEncryptor encryptorWithPassword(final String password) {
        final StrongTextEncryptor textEncryptor = new StrongTextEncryptor();
        textEncryptor.setPassword(password);
        return textEncryptor;
    }

    @Test
    void encryptProducesCiphertextThatDecryptsToOriginalText() {
        final String encrypted = encryptor.encrypt("secret value");

        assertNotEquals("secret value", encrypted);
        assertFalse(encrypted.contains("secret"));
        assertEquals("secret value", encryptor.decrypt(encrypted));
    }

    @Test
    void encryptUsesARandomSaltSoRepeatedCallsDiffer() {
        final String first = encryptor.encrypt("same text");
        final String second = encryptor.encrypt("same text");

        assertNotEquals(first, second);
        assertEquals("same text", encryptor.decrypt(first));
        assertEquals("same text", encryptor.decrypt(second));
    }

    @Test
    void encryptUsesTheConfiguredPassword() {
        final String encrypted = encryptor.encrypt("configured");

        assertEquals("configured", encryptorWithPassword(PASSWORD).decrypt(encrypted));
        assertThrows(EncryptionOperationNotPossibleException.class,
                () -> encryptorWithPassword("other-password").decrypt(encrypted));
    }

    @Test
    void decryptUsesTheConfiguredPassword() {
        assertEquals("from elsewhere", encryptor.decrypt(encryptorWithPassword(PASSWORD).encrypt("from elsewhere")));
    }

    @Test
    void decryptFailsForTextEncryptedWithADifferentPassword() {
        final String encrypted = encryptorWithPassword("other-password").encrypt("hidden");

        assertThrows(EncryptionOperationNotPossibleException.class, () -> encryptor.decrypt(encrypted));
    }

    @Test
    void decryptFailsForTextThatIsNotEncrypted() {
        assertThrows(EncryptionOperationNotPossibleException.class, () -> encryptor.decrypt("not encrypted"));
    }

    @Test
    void emptyTextRoundTrips() {
        final String encrypted = encryptor.encrypt("");

        assertEquals("", encryptor.decrypt(encrypted));
    }
}
