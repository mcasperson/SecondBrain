package secondbrain.infrastructure.youtube;

import com.google.common.util.concurrent.RateLimiter;
import io.github.thoroldvix.api.Transcript;
import io.github.thoroldvix.api.TranscriptApiFactory;
import io.github.thoroldvix.api.TranscriptContent;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.ArrayUtils;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.httpclient.HttpClientCaller;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.response.ResponseValidation;
import secondbrain.infrastructure.youtube.api.YoutubePlaylists;
import secondbrain.infrastructure.youtube.api.YoutubePlaylistsItem;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class YoutubeClientLive implements YoutubeClient {
    private static final RateLimiter RATE_LIMITER = RateLimiter.create(Constants.DEFAULT_RATE_LIMIT_PER_SECOND);
    private static final long API_CONNECTION_TIMEOUT_SECONDS_DEFAULT = 10;
    private static final long API_CALL_TIMEOUT_SECONDS_DEFAULT = 60 * 2; // 2 minutes
    private static final long CLIENT_TIMEOUT_BUFFER_SECONDS = 5;

    @Inject
    private ResponseValidation responseValidation;

    @Inject
    private LocalStorage localStorage;

    @Inject
    private Logger logger;

    @Inject
    private HttpClientCaller httpClientCaller;

    private Client getClient() {
        final ClientBuilder clientBuilder = ClientBuilder.newBuilder();
        clientBuilder.connectTimeout(API_CONNECTION_TIMEOUT_SECONDS_DEFAULT, TimeUnit.SECONDS);
        // We want to use the timeoutService to handle timeouts, so we set the client timeout slightly longer.
        clientBuilder.readTimeout(API_CALL_TIMEOUT_SECONDS_DEFAULT + CLIENT_TIMEOUT_BUFFER_SECONDS, TimeUnit.SECONDS);
        return clientBuilder.build();
    }

    @Override
    public List<YoutubePlaylistsItem> getPlaylistItems(final String playlistId, final String pageToken, final String key) {
        final YoutubePlaylistsItem[] items = localStorage.getOrPutObject(
                        YoutubeClientLive.class.getSimpleName(),
                        "YoutubeAPIPlaylists",
                        playlistId,
                        YoutubePlaylistsItem[].class,
                        () -> getPlaylistItemsApi(playlistId, pageToken, key))
                .result();

        return Arrays.asList(items);
    }

    @Override
    public String getTranscript(final String videoId, final String lang) {
        return Try.of(TranscriptApiFactory::createDefault)
                .mapTry(api -> api.listTranscripts(videoId))
                .mapTry(transcripts -> transcripts.findGeneratedTranscript(lang))
                .mapTry(Transcript::fetch)
                .map(transcript -> transcript.getContent()
                        .stream()
                        .map(TranscriptContent.Fragment::getText)
                        .reduce("", (a, b) -> a + "\n" + b))
                .get();
    }

    private YoutubePlaylistsItem[] getPlaylistItemsApi(final String playlistId, final String pageToken, final String key) {
        logger.log(Level.INFO, "Getting Youtube API playlist " + playlistId);

        RATE_LIMITER.acquire();

        final String target = "https://www.googleapis.com/youtube/v3/playlistItems?"
                + "part=snippet"
                + "&maxResults=50"
                + "&playlistId=" + playlistId
                + "&key=" + key
                + (StringUtils.isNotBlank(pageToken) ? "&pageToken=" + pageToken : "");

        return httpClientCaller.call(
                this::getClient,
                client -> client.target(target)
                        .request()
                        .header("Accept", MediaType.APPLICATION_JSON)
                        .get(),
                response -> Try.of(() -> responseValidation.validate(response, target))
                        .map(r -> r.readEntity(YoutubePlaylists.class))
                        // Recurse if there is a next page, and we have not gone too far
                        .map(r -> ArrayUtils.addAll(
                                r.getItemsArray(),
                                StringUtils.isNotBlank(r.nextPageToken())
                                        ? getPlaylistItemsApi(playlistId, r.nextPageToken(), key)
                                        : new YoutubePlaylistsItem[]{}))
                        .get(),
                cause -> new RuntimeException("Failed to get Youtube playlist", cause)
        );
    }
}
