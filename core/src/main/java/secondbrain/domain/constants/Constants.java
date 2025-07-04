package secondbrain.domain.constants;

public class Constants {
    /**
     * This number has been picked to avoid most api rate limits. It was mostly trial and error, and there
     * may be a better number to use.
     */
    public static final int DEFAULT_SEMAPHORE_COUNT = 5;

    public static final double DEFAULT_RATE_LIMIT_PER_SECOND = 0.25;

    /**
     * The default content window size. Increasing this value increases the memory
     * usage of the application. 4096 is about as much as you can define for a system with 32 GB of RAM.
     */
    public static final int DEFAULT_CONTENT_WINDOW = 4096;

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
