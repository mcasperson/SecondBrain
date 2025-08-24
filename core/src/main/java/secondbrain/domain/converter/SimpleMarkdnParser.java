package secondbrain.domain.converter;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.regex.Pattern;

@ApplicationScoped
public class SimpleMarkdnParser implements StringConverter {
    private static final Pattern URL_MATCHER = java.util.regex.Pattern.compile("<((?:http|https)://[^|]+).*?>");

    @Inject
    private MarkdnParser markdnParser;

    @Override
    public String getFormat() {
        return "simplemarkdn";
    }

    @Override
    public String convert(String response) {
        final String parsed = markdnParser.convert(response);
        // Remove the links, as these are not always rendered properly in Slack
        return URL_MATCHER.matcher(parsed).replaceAll("$1");
    }
}
