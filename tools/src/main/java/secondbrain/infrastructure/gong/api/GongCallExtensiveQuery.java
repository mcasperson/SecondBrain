package secondbrain.infrastructure.gong.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GongCallExtensiveQuery(GongCallExtensiveQueryFiler filter,
                                     GongCallExtensiveQueryContentSelector contentSelector,
                                     @Nullable String cursor) {
}
