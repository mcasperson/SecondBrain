package secondbrain.domain.encryption;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AesEncryptorTest {

    private AesEncryptor aesEncryptor;

    @BeforeEach
    void setUp() throws Exception {
        aesEncryptor = new AesEncryptor();
        setField(aesEncryptor, "encryptionPassword", Optional.of("testPassword123"));
        setField(aesEncryptor, "salt", Optional.of("testSalt1234567"));
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void encryptProducesNonNullResult() {
        String encrypted = aesEncryptor.encrypt("Hello, World!");
        assertNotNull(encrypted);
        assertFalse(encrypted.isEmpty());
    }

    @Test
    void encryptProducesDifferentOutputThanInput() {
        String plaintext = "Hello, World!";
        String encrypted = aesEncryptor.encrypt(plaintext);
        assertNotEquals(plaintext, encrypted);
    }

    @Test
    void encryptAndDecryptRoundTrip() {
        String plaintext = "Hello, World!";
        String encrypted = aesEncryptor.encrypt(plaintext);
        String decrypted = aesEncryptor.decrypt(encrypted);
        assertEquals(plaintext, decrypted);
    }

    @Test
    void encryptProducesUniqueOutputEachCall() {
        String plaintext = "Hello, World!";
        String encrypted1 = aesEncryptor.encrypt(plaintext);
        String encrypted2 = aesEncryptor.encrypt(plaintext);
        // Each encryption uses a random IV, so results should differ
        assertNotEquals(encrypted1, encrypted2);
    }

    @Test
    void encryptAndDecryptEmptyString() {
        String plaintext = "";
        String encrypted = aesEncryptor.encrypt(plaintext);
        String decrypted = aesEncryptor.decrypt(encrypted);
        assertEquals(plaintext, decrypted);
    }

    @Test
    void encryptAndDecryptLongString() {
        String plaintext = "a".repeat(10000);
        String encrypted = aesEncryptor.encrypt(plaintext);
        String decrypted = aesEncryptor.decrypt(encrypted);
        assertEquals(plaintext, decrypted);
    }

    @Test
    void encryptAndDecryptSpecialCharacters() {
        String plaintext = "!@#$%^&*()_+-=[]{}|;':\",./<>?`~\n\t";
        String encrypted = aesEncryptor.encrypt(plaintext);
        String decrypted = aesEncryptor.decrypt(encrypted);
        assertEquals(plaintext, decrypted);
    }

    @Test
    void decryptWithWrongPasswordFails() throws Exception {
        String encrypted = aesEncryptor.encrypt("secret");

        AesEncryptor wrongEncryptor = new AesEncryptor();
        setField(wrongEncryptor, "encryptionPassword", Optional.of("wrongPassword!"));
        setField(wrongEncryptor, "salt", Optional.of("testSalt1234567"));

        assertThrows(Exception.class, () -> wrongEncryptor.decrypt(encrypted));
    }

    @Test
    void decryptWithWrongSaltFails() throws Exception {
        String encrypted = aesEncryptor.encrypt("secret");

        AesEncryptor wrongEncryptor = new AesEncryptor();
        setField(wrongEncryptor, "encryptionPassword", Optional.of("testPassword123"));
        setField(wrongEncryptor, "salt", Optional.of("wrongSalt123456"));

        assertThrows(Exception.class, () -> wrongEncryptor.decrypt(encrypted));
    }
}

