package secondbrain.infrastructure.gong.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GongCallExtensive(GongCallExtensiveMetadata metaData,
                                List<GongCallExtensiveContext> context,
                                List<GongCallExtensiveParty> parties) {

    public Optional<GongCallExtensiveContext> getSystemContext(final String system) {
        return Objects.requireNonNullElse(context(), List.<GongCallExtensiveContext>of())
                .stream()
                .filter(c -> system.equals(c.system()))
                .findFirst();
    }
}
