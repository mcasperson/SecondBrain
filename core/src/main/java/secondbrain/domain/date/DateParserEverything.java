package secondbrain.domain.date;

import io.smallrye.common.annotation.Identifier;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.inject.Inject;

import java.time.ZonedDateTime;

@ApplicationScoped
@Identifier("everything")
public class DateParserEverything implements DateParser {

    @Inject
    @Identifier("hawking")
    private DateParser dateParserHawking;

    @Inject
    private DateParser dateParserIso8601;

    @Inject
    @Identifier("unix")
    private DateParser dateParserUnix;

    @Inject
    @Identifier("yyyyMmDd")
    private DateParser dateParserYyyyMmDd;

    @Override
    public ZonedDateTime parseDate(final String date) {
        return Try.of(() -> dateParserUnix.parseDate(date))
                .recoverWith(error -> Try.of(() -> dateParserIso8601.parseDate(date)))
                .recoverWith(error -> Try.of(() -> dateParserYyyyMmDd.parseDate(date)))
                .recoverWith(error -> Try.of(() -> dateParserHawking.parseDate(date)))
                .get();
    }
}
