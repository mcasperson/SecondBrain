package secondbrain.infrastructure.dovetail.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DovetailDataItem(
        String id,
        String type,
        String title,
        DovetailDataProject project,
        @JsonProperty("created_at") String createdAt,
        boolean deleted,
        @Nullable DovetailDataFolder folder
) {
    public Optional<DovetailDataFolder> getFolder() {
        return Optional.ofNullable(folder);
    }
}

