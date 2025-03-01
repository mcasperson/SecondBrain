package secondbrain.domain.answer;

import org.apache.poi.util.StringUtil;

/**
 * A formatter to remove the thinking part of the Deepseek response from the deepseek-r1 model.
 */
public class AnswerFormatterDeepseek implements AnswerFormatter {
    @Override
    public String modelRegex() {
        return "^deepseek-r1.*$";
    }

    @Override
    public String formatAnswer(final String answer) {
        if (StringUtil.isBlank(answer)) {
            return "";
        }
        return answer.replaceAll("<think>.*?</think>", "");
    }
}
