package secondbrain.domain.prompt;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.constants.ModelRegex;

import java.util.Optional;

/**
 * See <a href="https://ollama.com/library/qwen2/blobs/62fbfd9ed093">qwen2</a>
 * for the template format.
 */
@ApplicationScoped
public class PromptBuilderQwen implements PromptBuilder {

    @Inject
    @ConfigProperty(name = "sb.qwen.thinking", defaultValue = "false")
    private Optional<String> thinking;

    @Override
    public String modelRegex() {
        return ModelRegex.QWEN_REGEX;
    }

    @Override
    public String buildContextPrompt(final String title, final String prompt) {
        if (StringUtils.isBlank(prompt)) {
            return "";
        }

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
        if (StringUtils.isBlank(instructions)) {
            return StringUtils.trim(context)
                    + "\n<|im_end|>\n"
                    + "\n<|im_start|>user\n"
                    + prompt
                    + "\n<|im_end|>"
                    + "\n<|im_start|>assistant";
        }

        return "<|im_start|>system\n"
                + StringUtils.trim(context)
                + "\n"
                + StringUtils.trim(instructions)
                + "\n<|im_end|>\n"
                + "\n<|im_start|>user\n"
                + prompt
                + "\n<|im_end|>"
                + getThinking()
                + "\n<|im_start|>assistant";
    }

    private String getThinking() {
        return thinking != null && !Boolean.parseBoolean(thinking.orElse("false"))
                ? """
                <|im_start|>system
                \\no_think
                <|im_end|>""".stripIndent()
                : "";
    }
}
