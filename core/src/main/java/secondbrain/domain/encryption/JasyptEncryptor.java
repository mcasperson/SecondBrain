package secondbrain.domain.encryption;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jasypt.util.text.BasicTextEncryptor;

@ApplicationScoped
public class JasyptEncryptor implements Encryptor {
    private final BasicTextEncryptor textEncryptor;
    @Inject
    @ConfigProperty(name = "sb.encryption.password", defaultValue = "12345678")
    String encryptionPassword;

    public JasyptEncryptor() {
        textEncryptor = new BasicTextEncryptor();
        textEncryptor.setPassword(encryptionPassword);
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
