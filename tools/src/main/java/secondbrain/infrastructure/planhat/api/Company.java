package secondbrain.infrastructure.planhat.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Company(@JsonProperty("_id") String id,
                      String name,
                      @Nullable String renewalDate,
                      Map<String, Integer> usage,
                      Map<String, Object> custom) {
}
