package secondbrain.domain.prompt;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PromptBuilderLlama3 implements PromptBuilder {

    @Override
    public String modelRegex() {
        return "^llama3.*$";
    }

    @Override
    public String buildContextPrompt(final String title, final String prompt) {
        /*
        See https://github.com/meta-llama/llama-recipes/issues/450 for a discussion
        on the preferred format (or lack thereof) for RAG context.
        */
        return "<|start_header_id|>system<|end_header_id|>\n"
                + title + ":\n"
                + prompt
                + "\n<|eot_id|>";
    }

    @Override
    public String buildFinalPrompt(final String instructions, final String context, final String prompt) {
        return "<|begin_of_text|>\n"
                + "<|start_header_id|>system<|end_header_id|>\n"
                + instructions
                + "\n<|eot_id|>\n"
                + context
                + "\n<|start_header_id|>user<|end_header_id|>\n"
                + prompt
                + "\n<|eot_id|>\n"
                + "<|start_header_id|>assistant<|end_header_id|>";
    }
}