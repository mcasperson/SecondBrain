package secondbrain.domain.model;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

@RequestScoped
@Named
public class Google {

    @Inject
    @ConfigProperty(name = "sb.google.redirecturl", defaultValue = "https://localhost:8181/api/google_oauth")
    Optional<String> googleRedirectUrl;

    @Inject
    @ConfigProperty(name = "sb.google.clientid")
    private
    Optional<String> googleClientId;

    public String getGoogleClientId() {
        return googleClientId.orElse("");
    }

    public String getGoogleRedirectUrl() {
        return googleRedirectUrl.orElse("");
    }
}
