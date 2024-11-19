package secondbrain.domain.prompt;

/**
 * Prompt builders provide a way to build up a prompt to be sent to the LLM.
 * Each LLM has its own template style that must be accommodated, but the tools
 * should not have to know this format.
 */
public interface PromptBuilder {
    String modelRegex();

    String buildContextPrompt(String title, String context);

    String buildFinalPrompt(String instructions, String context, String prompt);
}
