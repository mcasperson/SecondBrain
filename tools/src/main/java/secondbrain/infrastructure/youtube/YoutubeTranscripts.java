package secondbrain.infrastructure.youtube;

/**
 * Exposes the YouTube transcript operations used by {@link YoutubeClientLive} behind an interface.
 * The youtube-transcript-api classes have no interfaces, so this adapter allows them to be swapped for a test instance.
 */
public interface YoutubeTranscripts {
    /**
     * Lists the transcripts available for a video.
     *
     * @param videoId The ID of the video
     * @return The transcripts available for the video
     * @throws RuntimeException if the transcripts could not be listed
     */
    YoutubeTranscriptList listTranscripts(String videoId);
}
