package secondbrain.domain.constants;

public class Constants {
    /**
     * The maximum length of a context that can be passed to the Llama3.2 LLM hosted
     * by Ollama. The context window is 8K, with an average of 4 characters per
     * token. We have a buffer for the prompt, so we can use 7000 tokens.
     */
    public static final int MAX_CONTEXT_LENGTH = 7000 * 4;
}
