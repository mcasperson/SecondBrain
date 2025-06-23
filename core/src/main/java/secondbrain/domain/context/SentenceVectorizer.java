package secondbrain.domain.context;

import java.util.List;

/**
 * Vectorizes a sentence.
 */
public interface SentenceVectorizer {
    /**
     * Vectorize the sentence, embedding the hidden text into the vector. This is useful when the sentence needs
     * some additional context to be matched in the final output. Typically, the hidden text is the name of the
     * entity being referred to in the generated text. It typically requires that the LLM not use pronouns like
     * "it" or "they" in the generated text, but instead repeat the entity name in every sentence.
     *
     * @param text       The text to be vectorized and returned as a RagStringContext
     * @param hiddenText The hidden text to be embedded into the vector
     * @return A RagStringContext with the text and the vector
     */
    RagStringContext vectorize(String text, String hiddenText);

    /**
     * Vectorize the sentence, embedding the hidden text into the vector. This is useful when the sentence needs
     * some additional context to be matched in the final output. Typically, the hidden text is the name of the
     * entity being referred to in the generated text. It typically requires that the LLM not use pronouns like
     * "it" or "they" in the generated text, but instead repeat the entity name in every sentence.
     *
     * @param text       The list of text to be vectorized and returned as a RagStringContext
     * @param hiddenText The hidden text to be embedded into the vector
     * @return A list of RagStringContext with the text and the vector
     */
    List<RagStringContext> vectorize(List<String> text, String hiddenText);

    /**
     * Vectorize the sentence.
     *
     * @param text The text to be vectorized and returned as a RagStringContext
     * @return A RagStringContext with the text and the vector
     */
    RagStringContext vectorize(String text);

    /**
     * Vectorize the sentences.
     *
     * @param text The list of text to be vectorized and returned as a RagStringContext
     * @return A list of RagStringContext with the text and the vector
     */
    List<RagStringContext> vectorize(List<String> text);
}
