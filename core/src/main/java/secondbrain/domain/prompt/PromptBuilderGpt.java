package secondbrain.domain.prompt;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.constants.ModelRegex;

@ApplicationScoped
public class PromptBuilderGpt implements PromptBuilder {

    @Override
    public String modelRegex() {
        return ModelRegex.OSS_REGEX;
    }

    @Override
    public String buildContextPrompt(final String title, final String prompt) {
        if (StringUtils.isBlank(prompt)) {
            return "";
        }

        if (StringUtils.isBlank(title)) {
            return "<|start|>system<|message|>\n"
                    + prompt
                    + "\n";
        }

        return "<|start|>system<|message|>\n"
                + title + ":\n"
                + prompt
                + "\n";
    }

    @Override
    public String buildFinalPrompt(@Nullable final String instructions, final String context, final String prompt) {
        if (StringUtils.isBlank(instructions)) {
            return context
                    + "\n<|start|>user<|message|>\n"
                    + prompt
                    + "\n"
                    + "<|start|>assistant\n";
        }

        return context
                + "<|start|>system<|message|>\n"
                + instructions
                + "\n"
                + "\n<|start|>user<|message|>\n"
                + prompt
                + "\n"
                + "<|start|>assistant\n";
    }
}
