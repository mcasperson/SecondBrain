package secondbrain.domain.prompt;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * See <a href="https://github.com/microsoft/Phi-3CookBook/blob/main/md/02.QuickStart/Huggingface_QuickStart.md">phi3</a>
 * for the template format.
 */
@ApplicationScoped
public class PromptBuilderPhi3 implements PromptBuilder {

    @Override
    public String modelRegex() {
        return "^phi3.*$";
    }

    @Override
    public String buildContextPrompt(final String title, final String prompt) {
        return "<|system|>\n"
                + title + ":\n"
                + prompt
                + "\n<|end|>";
    }

    @Override
    public String buildFinalPrompt(final String instructions, final String context, final String prompt) {
        return "<|system|>\n"
                + instructions
                + "\n<|end|>\n"
                + context
                + "\n<|user|>\n"
                + prompt
                + "\n<|end|>\n"
                + "<|assistant|>";
    }
}
