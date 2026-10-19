package secondbrain.infrastructure.youtube;

import io.github.thoroldvix.api.Transcript;
import io.github.thoroldvix.api.TranscriptContent;
import io.github.thoroldvix.api.TranscriptList;
import io.github.thoroldvix.api.YoutubeTranscriptApi;
import io.vavr.CheckedFunction0;
import io.vavr.control.Try;

import java.util.List;
import java.util.Optional;

/**
 * Adapts the youtube-transcript-api YoutubeTranscriptApi to the YoutubeTranscripts interface.
 * Instances are created by {@link YoutubeTranscriptsProducer}.
 */
public class YoutubeTranscriptsLive implements YoutubeTranscripts {
    private final YoutubeTranscriptApi api;

    public YoutubeTranscriptsLive(final YoutubeTranscriptApi api) {
        this.api = api;
    }

    @Override
    public YoutubeTranscriptList listTranscripts(final String videoId) {
        final TranscriptList transcriptList = Try.of(() -> api.listTranscripts(videoId)).get();

        return new YoutubeTranscriptList() {
            @Override
            public Optional<List<String>> fetchManualTranscript(final String lang) {
                return fetch(() -> transcriptList.findManualTranscript(lang));
            }

            @Override
            public Optional<List<String>> fetchGeneratedTranscript(final String lang) {
                return fetch(() -> transcriptList.findGeneratedTranscript(lang));
            }
        };
    }

    /**
     * A transcript that can not be found is returned as an empty result. A transcript that is found but can not be
     * fetched is an error.
     */
    private Optional<List<String>> fetch(final CheckedFunction0<Transcript> find) {
        return Try.of(find)
                .toJavaOptional()
                .map(transcript -> Try.of(transcript::fetch)
                        .map(content -> content.getContent()
                                .stream()
                                .map(TranscriptContent.Fragment::getText)
                                .toList())
                        .get());
    }
}
