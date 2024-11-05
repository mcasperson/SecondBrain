package secondbrain.domain.model;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

@RequestScoped
@Named
public class LinkedIn {

    @Inject
    @ConfigProperty(name = "sb.linkedin.clientid")
    private
    Optional<String> linkedInClientId;

    @Inject
    @ConfigProperty(name = "sb.linkedin.redirectUri")
    private
    Optional<String> linkedInRedirectUri;

    public String getLinkedInClientId() {
        return linkedInClientId.orElse("");
    }

    public String getLinkedInRedirectUri() {
        return linkedInRedirectUri.orElse("");
    }
}
