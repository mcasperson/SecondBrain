package secondbrain.infrastructure.planhat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Company(@JsonProperty("_id") String id, String name, Map<String, Integer> usage) {
}
