package secondbrain.domain.encryption;

import org.jspecify.annotations.Nullable;

public interface Encryptor {
    String encrypt(String text);

    String decrypt(String text);
}
