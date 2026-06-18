package secondbrain.domain.sanitize;

import io.smallrye.common.annotation.Identifier;
import io.vavr.control.Try;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.tika.utils.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jooq.lambda.Seq;

import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Implements unredactions based on a number of regexes supplied as
 * config properties. Be careful with these regexes - a regex like [0-9]{10}
 * will have a lot of unintended matches, especially for cached items
 * that contain vectors. Always try to have explicit regexes, and quote
 * numbers, e.g. "[0-9]{10}".
 */
@ApplicationScoped
public class CustomUnredaction implements Unredaction {
    private static final int MATCH_WARNING = 1000;

    @Inject
    @ConfigProperty(name = "sb.unredaction.regex1")
    private Optional<String> regex1;

    @Inject
    @ConfigProperty(name = "sb.unredaction.regex2")
    private Optional<String> regex2;

    @Inject
    @ConfigProperty(name = "sb.unredaction.regex3")
    private Optional<String> regex3;

    @Inject
    @ConfigProperty(name = "sb.unredaction.regex4")
    private Optional<String> regex4;

    @Inject
    @ConfigProperty(name = "sb.unredaction.regex5")
    private Optional<String> regex5;

    @Inject
    @Identifier("financialLocationContactRedaction")
    private SanitizeDocument sanitizeDocument;

    @Inject
    private Logger logger;

    private final List<Pattern> compiledRegexes = new ArrayList<>();

    @PostConstruct
    void init() {
        compiledRegexes.addAll(Stream.of(regex1, regex2, regex3, regex4, regex5)
                .filter(Objects::nonNull)
                .filter(Optional::isPresent)
                .map(r -> Try.of(() -> Pattern.compile(r.get())))
                .filter(Try::isSuccess)
                .map(Try::get)
                .toList());
    }

    @Override
    public String unredact(final String original, final String redacted) {
        if (StringUtils.isBlank(original) || StringUtils.isBlank(redacted)) {
            return redacted;
        }

        if (compiledRegexes.isEmpty()) {
            return redacted;
        }

        final Set<Map.Entry<String, String>> replacements = compiledRegexes.stream()
                .flatMap(r -> Try.of(() -> r.matcher(original))
                        .map(m -> {
                            Set<Map.Entry<String, String>> matches = new LinkedHashSet<>();
                            while (m.find()) {
                                final String sanitized = sanitizeDocument.sanitize(m.group(), false);
                                if (!StringUtils.isBlank(sanitized)) {
                                    matches.add(Map.entry(sanitized, m.group()));

                                    if (matches.size() > MATCH_WARNING && matches.size() % MATCH_WARNING == 0) {
                                        logger.warning("CustomUnredaction regex " + r.pattern() + " has found " + matches.size() + " matches in the original document. This may indicate an overly broad regex, which could lead to unintended unredactions. Please review the regex and consider making it more specific.");
                                    }
                                }
                            }
                            return matches.stream();
                        })
                        .getOrElse(Stream::empty))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return Seq.seq(replacements).foldLeft(redacted,
                (fixed, replacement) -> fixed.replaceFirst(
                        Pattern.quote(replacement.getKey()),
                        Matcher.quoteReplacement(replacement.getValue())));
    }
}
