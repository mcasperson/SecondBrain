package secondbrain.infrastructure.youtube;

import io.github.thoroldvix.api.Transcript;
import io.github.thoroldvix.api.TranscriptContent;
import io.github.thoroldvix.api.TranscriptList;
import io.github.thoroldvix.api.TranscriptRetrievalException;
import io.github.thoroldvix.api.YoutubeTranscriptApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link YoutubeTranscriptsLive}. The youtube-transcript-api objects are Mockito mocks, so no network
 * access is needed.
 */
class YoutubeTranscriptsLiveTest {
	private static final String VIDEO = "video-1";

	private final YoutubeTranscriptApi api = mock(YoutubeTranscriptApi.class);
	private final TranscriptList transcriptList = mock(TranscriptList.class);
	private final YoutubeTranscriptsLive live = new YoutubeTranscriptsLive(api);

	@BeforeEach
	void listTranscripts() throws TranscriptRetrievalException {
		when(api.listTranscripts(VIDEO)).thenReturn(transcriptList);
		when(transcriptList.findManualTranscript("en")).thenThrow(new TranscriptRetrievalException(VIDEO, "no manual transcript"));
		when(transcriptList.findGeneratedTranscript("en")).thenThrow(new TranscriptRetrievalException(VIDEO, "no generated transcript"));
	}

	private static Transcript transcript(final String... fragments) throws TranscriptRetrievalException {
		final Transcript transcript = mock(Transcript.class);
		when(transcript.fetch()).thenReturn(new TranscriptContent(
				Arrays.stream(fragments)
						.map(text -> new TranscriptContent.Fragment(text, 0, 1))
						.toList()));
		return transcript;
	}

	@Test
	void fetchManualTranscriptReturnsTheFragmentTextInOrder() throws TranscriptRetrievalException {
		final Transcript manual = transcript("Hello", "world");
		doReturn(manual).when(transcriptList).findManualTranscript("de");

		assertEquals(Optional.of(List.of("Hello", "world")), live.listTranscripts(VIDEO).fetchManualTranscript("de"));
	}

	@Test
	void fetchGeneratedTranscriptUsesTheGeneratedTranscript() throws TranscriptRetrievalException {
		final Transcript generated = transcript("auto", "captions");
		doReturn(generated).when(transcriptList).findGeneratedTranscript("de");
		doReturn(transcript("manual")).when(transcriptList).findManualTranscript("de");

		assertEquals(Optional.of(List.of("auto", "captions")), live.listTranscripts(VIDEO).fetchGeneratedTranscript("de"));
	}

	@Test
	void aTranscriptWithNoFragmentsIsAnEmptyList() throws TranscriptRetrievalException {
		doReturn(transcript()).when(transcriptList).findManualTranscript("de");

		assertEquals(Optional.of(List.of()), live.listTranscripts(VIDEO).fetchManualTranscript("de"));
	}

	@Test
	void missingTranscriptsAreEmpty() {
		final YoutubeTranscriptList list = live.listTranscripts(VIDEO);

		assertEquals(Optional.empty(), list.fetchManualTranscript("en"));
		assertEquals(Optional.empty(), list.fetchGeneratedTranscript("en"));
	}

	@Test
	void aTranscriptThatCanNotBeFetchedIsAnError() throws TranscriptRetrievalException {
		final Transcript broken = mock(Transcript.class);
		final TranscriptRetrievalException failure = new TranscriptRetrievalException(VIDEO, "fetch failed");
		when(broken.fetch()).thenThrow(failure);
		doReturn(broken).when(transcriptList).findGeneratedTranscript("de");

		final TranscriptRetrievalException thrown = assertThrows(TranscriptRetrievalException.class,
				() -> live.listTranscripts(VIDEO).fetchGeneratedTranscript("de"));

		assertSame(failure, thrown);
	}

	@Test
	void listingFailuresArePropagated() throws TranscriptRetrievalException {
		final TranscriptRetrievalException failure = new TranscriptRetrievalException("missing", "video unavailable");
		when(api.listTranscripts("missing")).thenThrow(failure);

		final TranscriptRetrievalException thrown = assertThrows(TranscriptRetrievalException.class, () -> live.listTranscripts("missing"));

		assertSame(failure, thrown);
	}
}
