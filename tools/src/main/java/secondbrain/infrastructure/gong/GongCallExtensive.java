package secondbrain.infrastructure.gong;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GongCallExtensive(GongCallExtensiveMetadata metaData, List<GongCallExtensiveContext> context) {
}
