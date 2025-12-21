package secondbrain.domain.answer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.poi.util.StringUtil;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.constants.ModelRegex;

/**
 * A formatter to remove the thinking part of the nemotron response.
 */
@ApplicationScoped
public class AnswerFormatterNemotron implements AnswerFormatter {
    @Inject
    @ConfigProperty(name = "sb.answerformatter.nemotonrregex", defaultValue = ModelRegex.NEMOTRON_REGEX)
    private String modelRegex;

    @Override
    public String modelRegex() {
        return modelRegex;
    }

    @Override
    public String formatAnswer(final String answer) {
        if (StringUtil.isBlank(answer)) {
            return "";
        }

        return answer
                .replaceAll("(?s)<think>.*?</think>", "")
                // Sometimes the start tag is not present
                .replaceAll("(?s)^.*?</think>", "")
                .trim();
    }
}
