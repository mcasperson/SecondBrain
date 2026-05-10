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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class CustomUnredaction implements Unredaction {
    @Inject
    @ConfigProperty(name = "sb.unredaction.regex1")
    private Optional<String> regex1;

    @Inject
    @Identifier("financialLocationContactRedaction")
    private SanitizeDocument sanitizeDocument;

    private Optional<Pattern> compiledRegex1 = Optional.empty();

    @PostConstruct
    void init() {
        compiledRegex1 = regex1.flatMap(value -> Try.of(() -> Pattern.compile(value)).toJavaOptional());
    }

    @Override
    public String unredact(final String original, final String redacted) {
        if (StringUtils.isBlank(original) || StringUtils.isBlank(redacted)) {
            return redacted;
        }

        if (compiledRegex1.isEmpty()) {
            return redacted;
        }

        final Set<Map.Entry<String, String>> replacements = Try.of(() -> compiledRegex1.get().matcher(original))
                .map(m -> {
                    Set<Map.Entry<String, String>> matches = new LinkedHashSet<>();
                    while (m.find()) {
                        final String sanitized = sanitizeDocument.sanitize(m.group());
                        if (!StringUtils.isBlank(sanitized)) {
                            matches.add(Map.entry(sanitized, m.group()));
                        }
                    }
                    return matches;
                })
                .getOrElse(Collections::emptySet);

        return Seq.seq(replacements).foldLeft(redacted,
                (fixed, replacement) -> fixed.replaceFirst(
                        Pattern.quote(replacement.getKey()),
                        Matcher.quoteReplacement(replacement.getValue())));
    }
}
