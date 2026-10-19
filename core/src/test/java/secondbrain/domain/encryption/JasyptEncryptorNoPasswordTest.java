package secondbrain.domain.encryption;

import io.smallrye.common.annotation.Identifier;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import secondbrain.domain.exceptionhandling.LoggingExceptionHandler;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.test.TestConfigUtil;

import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies that {@link JasyptEncryptor} can not be used when sb.encryption.password is not set.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(JasyptEncryptor.class)
@AddBeanClasses(Loggers.class)
@AddBeanClasses(LoggingExceptionHandler.class)
class JasyptEncryptorNoPasswordTest {
    @Inject
    @Identifier("Jasypt")
    private Encryptor encryptor;

    @BeforeAll
    static void registerConfig() {
        TestConfigUtil.registerConfig(Map.of());
    }

    @Test
    void firstUseFailsWhenPasswordIsMissing() {
        final Exception ex = assertThrows(Exception.class, () -> encryptor.encrypt("text"));

        assertInstanceOf(NoSuchElementException.class, ExceptionUtils.getRootCause(ex));
    }
}
