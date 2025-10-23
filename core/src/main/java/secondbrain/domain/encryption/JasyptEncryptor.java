package secondbrain.domain.encryption;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jasypt.util.text.StrongTextEncryptor;

import java.util.Optional;

@ApplicationScoped
public class JasyptEncryptor implements Encryptor {
    private final StrongTextEncryptor textEncryptor = new StrongTextEncryptor();

    @Inject
    @ConfigProperty(name = "sb.encryption.password")
    private Optional<String> encryptionPassword;

    @PostConstruct
    public void construct() {
        // throw if the password was not set
        textEncryptor.setPassword(encryptionPassword.get());
    }

    @Override
    public String encrypt(String text) {
        return textEncryptor.encrypt(text);
    }

    @Override
    public String decrypt(String text) {
        return textEncryptor.decrypt(text);
    }
}
