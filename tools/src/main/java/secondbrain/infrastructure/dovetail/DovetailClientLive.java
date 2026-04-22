package secondbrain.infrastructure.dovetail;

import com.google.common.util.concurrent.RateLimiter;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.httpclient.HttpClientCaller;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.mutex.Mutex;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.persist.TimedOperation;
import secondbrain.domain.response.ResponseValidation;
import secondbrain.infrastructure.dovetail.api.DovetailDataExportResponse;
import secondbrain.infrastructure.dovetail.api.DovetailDataItem;
import secondbrain.infrastructure.dovetail.api.DovetailDataList;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;

@ApplicationScoped
public class DovetailClientLive implements DovetailClient {

    private static final int TTL = 60 * 60 * 24 * 90;
    private static final RateLimiter RATE_LIMITER = RateLimiter.create(Constants.DEFAULT_RATE_LIMIT_PER_SECOND);
    private static final long API_CONNECTION_TIMEOUT_SECONDS_DEFAULT = 10;
    private static final long API_CALL_TIMEOUT_SECONDS_DEFAULT = 60 * 2; // 2 minutes
    private static final long CLIENT_TIMEOUT_BUFFER_SECONDS = 5;
    private static final long MUTEX_TIMEOUT_MS = 30 * 60 * 1000;
    private static final int MAX_PAGES = 100;

    @Inject
    @ConfigProperty(name = "sb.dovetail.lock", defaultValue = "sb_dovetail.lock")
    private String lockFile;

    @Inject
    private ResponseValidation responseValidation;

    @Inject
    @ConfigProperty(name = "sb.dovetail.url", defaultValue = "https://dovetail.com")
    private String url;

    @Inject
    @Preferred
    private LocalStorage localStorage;

    @Inject
    private Logger logger;

    @Inject
    private HttpClientCaller httpClientCaller;

    @Inject
    @Preferred
    private Mutex mutex;

    private Client getClient() {
        final ClientBuilder clientBuilder = ClientBuilder.newBuilder();
        clientBuilder.connectTimeout(API_CONNECTION_TIMEOUT_SECONDS_DEFAULT, TimeUnit.SECONDS);
        clientBuilder.readTimeout(API_CALL_TIMEOUT_SECONDS_DEFAULT + CLIENT_TIMEOUT_BUFFER_SECONDS, TimeUnit.SECONDS);
        return clientBuilder.build();
    }

    @Override
    public List<DovetailDataItem> getDataItems(
            final String apiKey,
            @Nullable final String fromDateTime,
            @Nullable final String toDateTime) {

        final String toDateTimeFinal = StringUtils.isBlank(toDateTime)
                ? OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS).format(ISO_OFFSET_DATE_TIME)
                : toDateTime;

        final DovetailDataItem[] items = localStorage.getOrPutObjectArray(
                        DovetailClientLive.class.getSimpleName(),
                        "DovetailAPIDataItemsV1",
                        DigestUtils.sha256Hex(fromDateTime + toDateTimeFinal),
                        TTL,
                        DovetailDataItem.class,
                        DovetailDataItem[].class,
                        () -> getDataItemsApiLocked(apiKey, fromDateTime, toDateTimeFinal, null, 0))
                .result();

        if (items == null) {
            return List.of();
        }

        return List.of(items);
    }

    @Override
    public String exportDataItemAsMarkdown(
            final String apiKey,
            final String id) {

        return Try.of(() -> localStorage.getOrPutObject(
                                DovetailClientLive.class.getSimpleName(),
                                "DovetailAPIMarkdownExportV1",
                                id,
                                DovetailDataExportResponse.class,
                                () -> exportMarkdownApi(apiKey, id))
                        .result())
                .filter(Objects::nonNull)
                .map(response -> response.data() != null
                        ? Objects.requireNonNullElse(response.data().contentMarkdown(), "")
                        : "")
                .getOrElse("");
    }

    private DovetailDataItem[] getDataItemsApiLocked(
            final String apiKey,
            @Nullable final String fromDateTime,
            @Nullable final String toDateTime,
            @Nullable final String cursor,
            final int page) {

        if (page >= MAX_PAGES) {
            logger.warning("Reached maximum pages of " + MAX_PAGES + " when fetching Dovetail data items");
            return new DovetailDataItem[]{};
        }

        logger.fine("Getting Dovetail data items from " + fromDateTime + " to " + toDateTime + " with cursor " + cursor);

        final DovetailDataList result = localStorage.getOrPutObject(
                        DovetailClientLive.class.getSimpleName(),
                        "DovetailAPIDataListPageV1",
                        DigestUtils.sha256Hex(fromDateTime + toDateTime + Objects.requireNonNullElse(cursor, "")),
                        TTL,
                        DovetailDataList.class,
                        () -> callDataListApi(apiKey, fromDateTime, toDateTime, cursor))
                .result();

        final DovetailDataItem[] itemsArray = result != null ? result.getDataArray() : new DovetailDataItem[]{};
        final DovetailDataItem[] nextItemsArray = (result != null
                && result.page() != null
                && result.page().hasMore()
                && StringUtils.isNotBlank(result.page().nextCursor()))
                ? getDataItemsApiLocked(apiKey, fromDateTime, toDateTime, result.page().nextCursor(), page + 1)
                : new DovetailDataItem[]{};

        return ArrayUtils.addAll(itemsArray, nextItemsArray);
    }

    private DovetailDataList callDataListApi(
            final String apiKey,
            @Nullable final String fromDateTime,
            @Nullable final String toDateTime,
            @Nullable final String cursor) {
        return mutex.acquire(
                MUTEX_TIMEOUT_MS,
                lockFile,
                () -> callDataListApiLocked(apiKey, fromDateTime, toDateTime, cursor));
    }

    private DovetailDataList callDataListApiLocked(
            final String apiKey,
            @Nullable final String fromDateTime,
            @Nullable final String toDateTime,
            @Nullable final String cursor) {
        return Try.withResources(() -> new TimedOperation("Dovetail API call for data list"))
                .of(t -> callDataListApiTimed(apiKey, fromDateTime, toDateTime, cursor))
                .get();
    }

    private DovetailDataList callDataListApiTimed(
            final String apiKey,
            @Nullable final String fromDateTime,
            @Nullable final String toDateTime,
            @Nullable final String cursor) {
        RATE_LIMITER.acquire();

        final String target = url + "/api/v1/data";

        return httpClientCaller.call(
                this::getClient,
                client -> {
                    jakarta.ws.rs.client.WebTarget filteredTarget = client.target(target);
                    if (StringUtils.isNotBlank(fromDateTime)) {
                        filteredTarget = filteredTarget.queryParam("filter[created_at][gte]", fromDateTime);
                    }
                    if (StringUtils.isNotBlank(toDateTime)) {
                        filteredTarget = filteredTarget.queryParam("filter[created_at][lte]", toDateTime);
                    }
                    if (StringUtils.isNotBlank(cursor)) {
                        filteredTarget = filteredTarget.queryParam("page[start_cursor]", cursor);
                    }
                    return filteredTarget
                            .request(MediaType.APPLICATION_JSON_TYPE)
                            .header("Authorization", "Bearer " + apiKey)
                            .get();
                },
                response -> Try.of(() -> responseValidation.validate(response, target))
                        .map(r -> r.readEntity(DovetailDataList.class))
                        .get(),
                e -> new RuntimeException("Failed to get data items from Dovetail API: " + e.toString(), e));
    }

    private DovetailDataExportResponse exportMarkdownApi(
            final String apiKey,
            final String id) {
        return mutex.acquire(
                MUTEX_TIMEOUT_MS,
                lockFile,
                () -> exportMarkdownApiLocked(apiKey, id));
    }

    private DovetailDataExportResponse exportMarkdownApiLocked(
            final String apiKey,
            final String id) {
        return Try.withResources(() -> new TimedOperation("Dovetail API call for markdown export " + id))
                .of(t -> exportMarkdownApiTimed(apiKey, id))
                .get();
    }

    /**
     * https://dovetail.com/api/v1/data/{id}/export/markdown
     */
    private DovetailDataExportResponse exportMarkdownApiTimed(
            final String apiKey,
            final String id) {
        RATE_LIMITER.acquire();

        logger.fine("Exporting Dovetail data item " + id + " as markdown");

        final String target = url + "/api/v1/data/" + id + "/export/markdown";

        return httpClientCaller.call(
                this::getClient,
                client -> client.target(target)
                        .request(MediaType.APPLICATION_JSON_TYPE)
                        .header("Authorization", "Bearer " + apiKey)
                        .get(),
                response -> Try.of(() -> responseValidation.validate(response, target))
                        .map(r -> r.readEntity(DovetailDataExportResponse.class))
                        .get(),
                e -> new RuntimeException("Failed to export Dovetail data item " + id + " as markdown: " + e.toString(), e));
    }
}


