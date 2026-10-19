package secondbrain.domain.test;

import org.jspecify.annotations.Nullable;
import secondbrain.infrastructure.youtube.YoutubeTranscriptList;
import secondbrain.infrastructure.youtube.YoutubeTranscripts;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An in-memory test instance of YoutubeTranscripts. Expose it to the class being tested with a @Produces method.
 * Every call is recorded as "list:videoId", "manual:videoId:lang" or "generated:videoId:lang".
 */
public class FakeYoutubeTranscripts implements YoutubeTranscripts {
    private final Map<String, List<String>> manual = new HashMap<>();
    private final Map<String, List<String>> generated = new HashMap<>();
    private final Map<String, RuntimeException> fetchFailures = new HashMap<>();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    @Nullable
    private RuntimeException listFailure;

    /**
     * Adds a manually created transcript made of the supplied fragments.
     */
    public FakeYoutubeTranscripts addManualTranscript(final String videoId, final String lang, final String... fragments) {
        manual.put(videoId + ":" + lang, List.of(fragments));
        return this;
    }

    /**
     * Adds an automatically generated transcript made of the supplied fragments.
     */
    public FakeYoutubeTranscripts addGeneratedTranscript(final String videoId, final String lang, final String... fragments) {
        generated.put(videoId + ":" + lang, List.of(fragments));
        return this;
    }

    /**
     * Makes fetching the manual transcript for the video and language throw, as if it exists but could not be downloaded.
     */
    public FakeYoutubeTranscripts failManualFetch(final String videoId, final String lang, final RuntimeException failure) {
        fetchFailures.put(videoId + ":" + lang, failure);
        return this;
    }

    /**
     * Makes every subsequent attempt to list transcripts throw the supplied exception.
     */
    public FakeYoutubeTranscripts failListingWith(final RuntimeException failure) {
        listFailure = failure;
        return this;
    }

    /**
     * @return Every call made to the fake, in order.
     */
    public List<String> getCalls() {
        return List.copyOf(calls);
    }

    @Override
    public YoutubeTranscriptList listTranscripts(final String videoId) {
        calls.add("list:" + videoId);
        final RuntimeException failure = listFailure;
        if (failure != null) {
            throw failure;
        }

        return new YoutubeTranscriptList() {
            @Override
            public Optional<List<String>> fetchManualTranscript(final String lang) {
                calls.add("manual:" + videoId + ":" + lang);
                final RuntimeException fetchFailure = fetchFailures.get(videoId + ":" + lang);
                if (fetchFailure != null) {
                    throw fetchFailure;
                }
                return Optional.ofNullable(manual.get(videoId + ":" + lang));
            }

            @Override
            public Optional<List<String>> fetchGeneratedTranscript(final String lang) {
                calls.add("generated:" + videoId + ":" + lang);
                return Optional.ofNullable(generated.get(videoId + ":" + lang));
            }
        };
    }
}
