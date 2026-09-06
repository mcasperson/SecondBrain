package secondbrain.infrastructure.planhat.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EmailAttachment(@Nullable String id,
                              @Nullable String fileName,
                              @Nullable String mimeType,
                              @Nullable Integer size) {
    public String getId() {
        return Objects.requireNonNullElse(id, "");
    }

    public String getFileName() {
        return Objects.requireNonNullElse(fileName, "");
    }

    public String getMimeType() {
        return Objects.requireNonNullElse(mimeType, "");
    }

    public Integer getSize() {
        return Objects.requireNonNullElse(size, 0);
    }
}
