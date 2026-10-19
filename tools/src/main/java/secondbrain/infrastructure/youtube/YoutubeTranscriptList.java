package secondbrain.infrastructure.youtube;

import java.util.List;
import java.util.Optional;

/**
 * The transcripts available for a single video.
 */
public interface YoutubeTranscriptList {
    /**
     * Fetches the manually created transcript in the language.
     *
     * @param lang The language code
     * @return The text of each fragment of the transcript, or empty if there is no manual transcript in the language
     * @throws RuntimeException if the transcript exists but could not be fetched
     */
    Optional<List<String>> fetchManualTranscript(String lang);

    /**
     * Fetches the automatically generated transcript in the language.
     *
     * @param lang The language code
     * @return The text of each fragment of the transcript, or empty if there is no generated transcript in the language
     * @throws RuntimeException if the transcript exists but could not be fetched
     */
    Optional<List<String>> fetchGeneratedTranscript(String lang);
}
