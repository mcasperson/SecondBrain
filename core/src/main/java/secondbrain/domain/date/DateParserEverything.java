package secondbrain.domain.date;

import io.smallrye.common.annotation.Identifier;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.ZonedDateTime;

@ApplicationScoped
@Identifier("everything")
public class DateParserEverything implements DateParser {

    @Inject
    private DateParserHawking dateParserHawking;

    @Inject
    private DateParserIso8601 dateParserIso8601;

    @Inject
    private DateParserUnix dateParserUnix;

    @Override
    public ZonedDateTime parseDate(final String date) {
        return Try.of(() -> dateParserUnix.parseDate(date))
                .recoverWith(error -> Try.of(() -> dateParserIso8601.parseDate(date)))
                .recoverWith(error -> Try.of(() -> dateParserHawking.parseDate(date)))
                .get();
    }
}
