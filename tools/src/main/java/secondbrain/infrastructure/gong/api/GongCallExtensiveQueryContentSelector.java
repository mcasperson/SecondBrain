package secondbrain.infrastructure.gong.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GongCallExtensiveQueryContentSelector(String context, List<String> contextTiming,
                                                    GongCallExtensiveQueryExposedFields exposedFields) {
}
