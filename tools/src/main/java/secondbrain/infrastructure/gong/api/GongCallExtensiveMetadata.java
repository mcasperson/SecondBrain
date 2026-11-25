package secondbrain.infrastructure.gong.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.vavr.control.Try;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GongCallExtensiveMetadata(String id, String url, String title, String started, Integer duration) {
    private static final OffsetDateTime MIN_DATE = OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC);

    /**
     * Returns the call duration in seconds, or 0 if it is not available.
     *
     * @return The call duration in seconds, or 0 if it is not available
     */
    public int getDuration() {
        return duration == null ? 0 : duration;
    }

    /**
     * Parses the ISO 8601 started string (e.g. 2023-11-24T08:00:52-05:00).
     *
     * @return The started date, or the epoch (1970-01-01T00:00Z) if the value is missing or can not be parsed
     */
    public OffsetDateTime startedDate() {
        if (StringUtils.isBlank(started)) {
            return MIN_DATE;
        }

        return Try.of(() -> OffsetDateTime.parse(started)).getOrElse(MIN_DATE);
    }
}
