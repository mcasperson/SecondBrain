package secondbrain.domain.constants;

public class Constants {
    /**
     * The maximum length of a context that can be passed to the Llama3.2 LLM hosted
     * by Ollama. The context window is 128K, with an average of 4 characters per
     * token.
     */
    public static final int MAX_CONTEXT_LENGTH = 100000 * 4;
    /**
     * The default section size when trimming a document based on keywords.
     */
    public static final int DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH = 1000;
}
