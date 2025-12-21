package secondbrain.domain.prompt;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.StringUtils;

@ApplicationScoped
public class PromptBuilderNemotron implements PromptBuilder {

    @Override
    public String modelRegex() {
        return "^nemotron-3-.*$";
    }

    @Override
    public String buildContextPrompt(final String title, final String prompt) {
        if (StringUtils.isBlank(prompt)) {
            return "";
        }

        if (StringUtils.isBlank(title)) {
            return "<|im_start|>system\n"
                    + prompt
                    + "\n<|im_end|>";
        }

        return "<|im_start|>system\n"
                + title + ":\n"
                + prompt
                + "\n<|im_end|>";
    }

    @Override
    public String buildFinalPrompt(final String instructions, final String context, final String prompt) {
        return "<|im_start|>system\n"
                + instructions
                + "\n<|im_end|>\n"
                + context
                + "\n<|im_start|>user\n"
                + prompt
                + "\n<|im_end|>\n"
                + "<|im_start|>assistant\n";
    }
}
