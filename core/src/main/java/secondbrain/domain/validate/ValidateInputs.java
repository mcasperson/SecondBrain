package secondbrain.domain.validate;

public interface ValidateInputs {
    /**
     * Get a comma separated list from the argument supplied by the LLM. The input is checked
     * against common hallucinations.
     *
     * @param input The value of the argument
     * @return The validated argument
     */
    String getCommaSeparatedList(String input);

    /**
     * Get a comma separated list from the argument supplied by the LLM. The input is checked
     * against the prompt to make sure it was actually supplied, as well as being checked
     * for common hallucinations.
     *
     * @param prompt The prompt from which the argument was supplied
     * @param input  The value of the argument
     * @return The validated argument
     */
    String getCommaSeparatedList(String prompt, String input);
}
