package secondbrain.domain.encryption;

public interface BinaryEncryptor {
    byte[] encrypt(byte[] text);

    byte[] decrypt(byte[] text);
}
