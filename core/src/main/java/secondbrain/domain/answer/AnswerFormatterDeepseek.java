package secondbrain.domain.answer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.poi.util.StringUtil;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.constants.ModelRegex;

/**
 * A formatter to remove the thinking part of the Deepseek response from the deepseek-r1 model.
 */
@ApplicationScoped
public class AnswerFormatterDeepseek implements AnswerFormatter {
    @Inject
    @ConfigProperty(name = "sb.answerformatter.deepseekregex", defaultValue = ModelRegex.DEEPSEEK_REGEX)
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
