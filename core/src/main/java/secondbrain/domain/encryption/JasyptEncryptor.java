package secondbrain.domain.encryption;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jasypt.util.text.BasicTextEncryptor;

@ApplicationScoped
public class JasyptEncryptor implements Encryptor {
    private final BasicTextEncryptor textEncryptor = new BasicTextEncryptor();
    
    @Inject
    @ConfigProperty(name = "sb.encryption.password", defaultValue = "123456789")
    String encryptionPassword;

    @PostConstruct
    public void construct() {
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
