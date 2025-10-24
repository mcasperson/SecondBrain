package secondbrain.domain.encryption;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jasypt.util.binary.StrongBinaryEncryptor;

import java.util.Optional;

@ApplicationScoped
public class JasyptBinaryEncryptor implements BinaryEncryptor {
    private final StrongBinaryEncryptor binaryEncryptor = new StrongBinaryEncryptor();

    @Inject
    @ConfigProperty(name = "sb.encryption.password")
    private Optional<String> encryptionPassword;

    @PostConstruct
    public void construct() {
        // throw if the password was not set
        binaryEncryptor.setPassword(encryptionPassword.get());
    }

    @Override
    public byte[] encrypt(byte[] text) {
        return binaryEncryptor.encrypt(text);
    }

    @Override
    public byte[] decrypt(byte[] text) {
        return binaryEncryptor.decrypt(text);
    }
}
