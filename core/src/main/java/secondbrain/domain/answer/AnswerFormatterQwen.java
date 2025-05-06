package secondbrain.domain.answer;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.poi.util.StringUtil;

/**
 * A formatter to remove the thinking part of the Deepseek response from the deepseek-r1 model.
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
                .replaceAll("(?s)^.*?</think>", "");
    }
}
