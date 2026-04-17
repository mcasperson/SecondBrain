package secondbrain.domain.encryption;

import io.smallrye.common.annotation.Identifier;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.exceptions.InternalFailure;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

@ApplicationScoped
@Identifier("AES")
public class AesEncryptor implements Encryptor {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int AUTH_TAG_LENGTH = 128;

    @Inject
    @ConfigProperty(name = "sb.encryption.password")
    private Optional<String> encryptionPassword;

    @Inject
    @ConfigProperty(name = "sb.encryption.salt")
    private Optional<String> salt;

    @Override
    public String encrypt(final String text) {
        final GCMParameterSpec iv = generateIv();

        return Try.of(() -> Cipher.getInstance(ALGORITHM))
            .mapTry(cipher -> {
                cipher.init(Cipher.ENCRYPT_MODE, getKeyFromPassword(encryptionPassword.get(), salt.get()), iv);
                return cipher;
            })
            .mapTry(cipher -> cipher.doFinal(text.getBytes()))
            .map(encrypted -> ByteBuffer.allocate(iv.getIV().length + encrypted.length)
                .put(iv.getIV())
                .put(encrypted)
                .array())
            .map(cipherText -> Base64.getEncoder().encodeToString(cipherText))
            .mapFailure(API.Case(API.$(), ex -> new InternalFailure("Failed to encrypt text", ex)))
            .get();
    }

    @Override
    public String decrypt(final String text) {
        final byte[] ivAndEncrypted = Base64.getDecoder().decode(text);

        final GCMParameterSpec iv = generateIv(Arrays.copyOfRange(ivAndEncrypted, 0, IV_LENGTH));
        final byte[] encrypted = Arrays.copyOfRange(ivAndEncrypted, IV_LENGTH, ivAndEncrypted.length);

        return Try.of(() -> Cipher.getInstance(ALGORITHM))
            .mapTry(cipher -> {
                cipher.init(Cipher.DECRYPT_MODE, getKeyFromPassword(encryptionPassword.get(), salt.get()), iv);
                return cipher;
            })
            .mapTry(cipher -> cipher.doFinal(encrypted))
            .map(String::new)
            .mapFailure(API.Case(API.$(), ex -> new InternalFailure("Failed to decrypt text", ex)))
            .get();
    }

    private SecretKey getKeyFromPassword(final String password, final String salt) {
        final KeySpec spec = new PBEKeySpec(password.toCharArray(), salt.getBytes(), 65536, 256);

        return Try.of(() -> SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256"))
                .mapTry(factory ->  new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES"))
                .mapFailure(API.Case(API.$(), ex -> new InternalFailure("Failed generate secret key", ex)))
                .get();
    }

    private GCMParameterSpec generateIv() {
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        return generateIv(iv);
    }

    private GCMParameterSpec generateIv(final byte[] iv) {
        return new GCMParameterSpec(AUTH_TAG_LENGTH, iv);
    }
}
