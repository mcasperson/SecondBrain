package secondbrain.domain.encryption;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import secondbrain.domain.test.TestConfigUtil;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies that {@link JasyptBinaryEncryptor} can not be used when sb.encryption.password is not set.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(JasyptBinaryEncryptor.class)
class JasyptBinaryEncryptorNoPasswordTest {
	@Inject
	private BinaryEncryptor encryptor;

	@BeforeAll
	static void registerConfig() {
		TestConfigUtil.registerConfig(Map.of());
	}

	@Test
	void firstEncryptFailsWhenPasswordIsMissing() {
		final Exception ex = assertThrows(Exception.class,
				() -> encryptor.encrypt("text".getBytes(StandardCharsets.UTF_8)));

		assertInstanceOf(NoSuchElementException.class, ExceptionUtils.getRootCause(ex));
	}

	@Test
	void firstDecryptFailsWhenPasswordIsMissing() {
		final Exception ex = assertThrows(Exception.class, () -> encryptor.decrypt(new byte[]{1, 2, 3}));

		assertInstanceOf(NoSuchElementException.class, ExceptionUtils.getRootCause(ex));
	}
}
