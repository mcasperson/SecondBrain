package secondbrain.infrastructure.youtube;

import io.github.thoroldvix.api.TranscriptApiFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * A factory that creates the YoutubeTranscripts used by {@link YoutubeClientLive}. Tests replace this class with their
 * own producer method that returns a test instance of YoutubeTranscripts. There is no disposer, as the transcript API
 * holds no resources that need to be released.
 */
@ApplicationScoped
public class YoutubeTranscriptsProducer {
    @Produces
    public YoutubeTranscripts produceYoutubeTranscripts() {
        return new YoutubeTranscriptsLive(TranscriptApiFactory.createDefault());
    }
}
