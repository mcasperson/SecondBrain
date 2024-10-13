package secondbrain.domain.model;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

@RequestScoped
@Named
public class Slack {

    @Inject
    @ConfigProperty(name = "sb.slack.clientid")
    private
    Optional<String> slackClientId;

    public String getSlackClientId() {
        return slackClientId.orElse("");
    }
}
