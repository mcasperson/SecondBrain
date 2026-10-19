package secondbrain.domain.encryption;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jasypt.exceptions.EncryptionOperationNotPossibleException;
import org.jasypt.util.binary.StrongBinaryEncryptor;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import secondbrain.domain.test.TestConfigUtil;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(JasyptBinaryEncryptor.class)
class JasyptBinaryEncryptorTest {
	private static final String PASSWORD = "test-password";
	private static final byte[] PLAIN = "secret value".getBytes(StandardCharsets.UTF_8);

	@Inject
	private BinaryEncryptor encryptor;

	@BeforeAll
	static void registerConfig() {
		TestConfigUtil.registerConfig(Map.of("sb.encryption.password", PASSWORD));
	}

	private static StrongBinaryEncryptor encryptorWithPassword(final String password) {
		final StrongBinaryEncryptor binaryEncryptor = new StrongBinaryEncryptor();
		binaryEncryptor.setPassword(password);
		return binaryEncryptor;
	}

	@Test
	void encryptProducesCiphertextThatDecryptsToTheOriginalBytes() {
		final byte[] encrypted = encryptor.encrypt(PLAIN);

		assertFalse(Arrays.equals(PLAIN, encrypted));
		assertArrayEquals(PLAIN, encryptor.decrypt(encrypted));
	}

	@Test
	void encryptUsesARandomSaltSoRepeatedCallsDiffer() {
		final byte[] first = encryptor.encrypt(PLAIN);
		final byte[] second = encryptor.encrypt(PLAIN);

		assertFalse(Arrays.equals(first, second));
		assertArrayEquals(PLAIN, encryptor.decrypt(first));
		assertArrayEquals(PLAIN, encryptor.decrypt(second));
	}

	@Test
	void encryptUsesTheConfiguredPassword() {
		final byte[] encrypted = encryptor.encrypt(PLAIN);

		assertArrayEquals(PLAIN, encryptorWithPassword(PASSWORD).decrypt(encrypted));
		assertThrows(EncryptionOperationNotPossibleException.class,
				() -> encryptorWithPassword("other-password").decrypt(encrypted));
	}

	@Test
	void decryptUsesTheConfiguredPassword() {
		assertArrayEquals(PLAIN, encryptor.decrypt(encryptorWithPassword(PASSWORD).encrypt(PLAIN)));
	}

	@Test
	void decryptFailsForBytesEncryptedWithADifferentPassword() {
		final byte[] encrypted = encryptorWithPassword("other-password").encrypt(PLAIN);

		assertThrows(EncryptionOperationNotPossibleException.class, () -> encryptor.decrypt(encrypted));
	}

	@Test
	void decryptFailsForBytesThatAreNotEncrypted() {
		assertThrows(EncryptionOperationNotPossibleException.class, () -> encryptor.decrypt(PLAIN));
	}

	@Test
	void emptyInputRoundTrips() {
		final byte[] encrypted = encryptor.encrypt(new byte[0]);

		assertTrue(encrypted.length > 0);
		assertArrayEquals(new byte[0], encryptor.decrypt(encrypted));
	}

	@Test
	void arbitraryBinaryDataRoundTrips() {
		final byte[] binary = new byte[256];
		for (int i = 0; i < binary.length; i++) {
			binary[i] = (byte) i;
		}

		assertArrayEquals(binary, encryptor.decrypt(encryptor.encrypt(binary)));
	}
}
