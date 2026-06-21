package secondbrain.domain.date;

import com.google.common.base.Preconditions;
import com.zoho.hawking.HawkingTimeParser;
import com.zoho.hawking.datetimeparser.configuration.HawkingConfiguration;
import com.zoho.hawking.language.english.model.DatesFound;
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang.StringUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.Optional;
import java.util.logging.Logger;

@ApplicationScoped
@Identifier("hawking")
public class DateParserHawking implements DateParser {

    @Inject
    private Logger logger;

    public ZonedDateTime parseDate(final String date) {
        Preconditions.checkArgument(StringUtils.isNotBlank(date), "date can not be an empty string");

        final HawkingTimeParser parser = new HawkingTimeParser();
        final HawkingConfiguration config = new HawkingConfiguration();
        config.setDateFormat("MM/dd/yyyy");
        final DatesFound datesFound = parser.parse(date, new Date(), config, "eng");
        final Optional<ZonedDateTime> parsedDate = datesFound.getParserOutputs()
                .stream()
                .map(d -> d.getDateRange().getStart())
                .map(d -> ZonedDateTime.ofInstant(Instant.ofEpochMilli(d.getMillis()), ZoneId.systemDefault()))
                .findFirst();

        if (parsedDate.isEmpty()) {
            logger.warning("Unable to parse date with Hawking: " + date);
        }

        return parsedDate
                .orElseThrow(() -> new IllegalArgumentException("Unable to parse date: " + date));
    }
}
