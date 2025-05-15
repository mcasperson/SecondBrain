package secondbrain.domain.answer;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.poi.util.StringUtil;

/**
 * A formatter to remove the thinking part of the Qwen response from the qwen3 model.
 */
@ApplicationScoped
public class AnswerFormatterQwen implements AnswerFormatter {
    @Override
    public String modelRegex() {
        return "^qwen.*$";
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
