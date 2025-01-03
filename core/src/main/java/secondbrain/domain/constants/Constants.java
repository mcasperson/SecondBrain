package secondbrain.domain.constants;

public class Constants {
    /**
     * The default content window size. Increasing this value increases the memory
     * usage of the application.
     */
    public static final int DEFAULT_CONTENT_WINDOW = 8192;

    /**
     * The value to multiply the content window by to get the maximum context length.
     */
    public static final float CONTENT_WINDOW_BUFFER = 0.75f;

    /**
     * The number of characters per token.
     */
    public static final int CHARACTERS_PER_TOKEN = 4;

    public static final int DEFAULT_MAX_CONTEXT_LENGTH = (int) (DEFAULT_CONTENT_WINDOW * CONTENT_WINDOW_BUFFER * CHARACTERS_PER_TOKEN);


    /**
     * The default section size when trimming a document based on keywords.
     */
    public static final int DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH = 2000;
}
