package secondbrain.domain.prompt;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.StringUtils;

/**
 * See <a href="https://ollama.com/library/qwen2/blobs/62fbfd9ed093">qwen2</a>
 * for the template format.
 */
@ApplicationScoped
public class PromptBuilderQwen2 implements PromptBuilder {

    @Override
    public String modelRegex() {
        return "^(qwq|qwen2).*$";
    }

    @Override
    public String buildContextPrompt(final String title, final String prompt) {
        if (StringUtils.isBlank(title)) {
            return "---------------------\n"
                    + prompt + "\n"
                    + "---------------------";
        }

        return "---------------------\n"
                + title + ":\n"
                + prompt + "\n"
                + "---------------------";
    }

    @Override
    public String buildFinalPrompt(final String instructions, final String context, final String prompt) {
        return "<|im_start|>system\n"
                + StringUtils.trim(instructions)
                + "\n"
                + StringUtils.trim(context)
                + "\n<|im_end|>\n"
                + "\n<|im_start|>user\n"
                + prompt
                + "\n<|im_end|>"
                + "\n<|im_start|>assistant";
    }
}
