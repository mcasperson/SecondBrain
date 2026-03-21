package secondbrain.domain.date;

import com.google.common.base.Preconditions;
import com.zoho.hawking.HawkingTimeParser;
import com.zoho.hawking.datetimeparser.configuration.HawkingConfiguration;
import com.zoho.hawking.language.english.model.DatesFound;
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang.StringUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;

@ApplicationScoped
@Identifier("hawking")
public class DateParserHawking implements DateParser {
    public ZonedDateTime parseDate(final String date) {
        Preconditions.checkArgument(StringUtils.isNotBlank(date), "date can not be an empty string");

        final HawkingTimeParser parser = new HawkingTimeParser();
        final HawkingConfiguration config = new HawkingConfiguration();
        config.setDateFormat("MM/dd/yyyy");
        final DatesFound datesFound = parser.parse(date, new Date(), config, "eng");
        return datesFound.getParserOutputs()
                .stream()
                .map(d -> d.getDateRange().getStart())
                .map(parsedDate -> ZonedDateTime.ofInstant(Instant.ofEpochMilli(parsedDate.getMillis()), ZoneId.systemDefault()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unable to parse date: " + date));
    }
}
