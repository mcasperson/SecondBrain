package secondbrain.domain.prompt;

/**
 * Prompt builders provide a way to build up a prompt to be sent to the LLM.
 * Each LLM has its own template style that must be accommodated, but the tools
 * should not have to know this format.
 */
public interface PromptBuilder {
    String modelRegex();

    /**
     * Builds the context to be included as part of a prompt.
     *
     * @param title   The title of the context, used to identify the context in the prompt.
     * @param context The context to be included in the prompt
     * @return A string with the formatted context based on the LLM's template style.
     */
    String buildContextPrompt(String title, String context);

    /**
     * Builds the final prompt to be sent to the LLM.
     *
     * @param instructions The instructions for the LLM, such as what to do with the context.
     * @param context      The context to be included in the prompt, built using {@link #buildContextPrompt(String, String)}.
     * @param prompt       The main prompt text to be sent to the LLM.
     * @return A string with the final prompt formatted according to the LLM's template style.
     */
    String buildFinalPrompt(String instructions, String context, String prompt);
}
