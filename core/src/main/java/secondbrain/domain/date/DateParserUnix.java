package secondbrain.domain.date;

import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@ApplicationScoped
@Identifier("unix")
public class DateParserUnix implements DateParser {
    @Override
    public ZonedDateTime parseDate(final String date) {
        final String[] parts = date.split("\\.");
        return ZonedDateTime.ofInstant(Instant.ofEpochSecond(Long.parseLong(parts[0])), ZoneId.systemDefault());
    }
}
