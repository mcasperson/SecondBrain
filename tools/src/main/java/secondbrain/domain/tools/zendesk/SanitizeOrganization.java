package secondbrain.domain.tools.zendesk;

import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.StringUtils;
import secondbrain.domain.sanitize.SanitizeArgument;

import java.util.Arrays;

/**
 * Sanitize an organization address passed in as an argument.
 */
@ApplicationScoped
@Identifier("sanitizeOrganization")
public class SanitizeOrganization implements SanitizeArgument {
    @Override
    public String sanitize(final String argument, final String document) {
        if (StringUtils.isBlank(argument)) {
            return "";
        }

        // A bunch of hallucinations that we need to ignore
        final String[] invalid = {
                "zendesk",
                "zendesk organization",
                "support",
                "helpdesk",
                "help desk",
                "help",
                "desk",
                "supportdesk",
                "support desk",
                "organization name",
                "organization",
                "name",
                "your organization name",
                "null"};

        if (Arrays.stream(invalid).anyMatch(argument::equalsIgnoreCase)) {
            return "";
        }

        return argument;
    }
}
