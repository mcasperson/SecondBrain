package secondbrain.infrastructure.gong;

import com.google.common.util.concurrent.RateLimiter;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.exceptions.InvalidResponse;
import secondbrain.domain.httpclient.HttpClientCaller;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.mutex.Mutex;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.persist.TimedOperation;
import secondbrain.domain.response.ResponseValidation;
import secondbrain.domain.web.ClientConstructor;
import secondbrain.infrastructure.gong.api.*;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.Logger;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;

@ApplicationScoped
@Preferred
public class GongClientLive implements GongClient {
    private static final int TTL = 60 * 60 * 24 * 90;
    private static final String CALLS_CACHE_SOURCE = "GongAPICallsExtensiveParentV2";
    private static final RateLimiter RATE_LIMITER = RateLimiter.create(Constants.DEFAULT_RATE_LIMIT_PER_SECOND);
    private static final int MAX_PAGES = 100;

    @Inject
    @ConfigProperty(name = "sb.gong.lock", defaultValue = "sb_gong.lock")
    private String lockFile;

    @Inject
    private ResponseValidation responseValidation;

    @Inject
    @ConfigProperty(name = "sb.gong.url", defaultValue = "https://api.gong.io")
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

    @Inject
    private ClientConstructor clientConstructor;

    @Override
    public List<GongCallExtensive> getCallsExtensive(
            final String company,
            @Nullable final String callId,
            final String username,
            final String password,
            @Nullable final String fromDateTime,
            @Nullable final String toDateTime) {
        // Build a "to" date for the cache key
        final String toDateTimeFinal = StringUtils.isBlank(toDateTime)
                ? OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS).format(ISO_OFFSET_DATE_TIME)
                : toDateTime;

        final String hash = DigestUtils.sha256Hex(fromDateTime + toDateTimeFinal + callId);

        /*
            Cache at the parent level to take advantage of the local cache, even if the remote cache
            (CosmoDB) has limits on the size of each cached object.
         */
        final GongCallExtensive[] calls = Try.of(() -> localStorage.getOrPutObjectArray(
                        GongClientLive.class.getSimpleName(),
                        CALLS_CACHE_SOURCE,
                        hash,
                        TTL,
                        GongCallExtensive.class,
                        GongCallExtensive[].class,
                        () -> getCallsExtensiveApi(fromDateTime, toDateTimeFinal, callId, username, password))
                .result()
        ).recover(GongAPIException.class, e -> {
            // I have seen cases where the gong result was stopped half-way through and the
            // cursor went stale. Every attempt to load the result resulted in failure.
            // In this case, we get a fresh copy of the data and do not load it from the cache.
            if (e.getCause() instanceof InvalidResponse invalidResponse) {
                if (invalidResponse.getBody().contains("cursor has expired")) {
                    return localStorage.persistArrayResult(
                                    GongClientLive.class.getSimpleName(),
                                    CALLS_CACHE_SOURCE,
                                    hash,
                                    TTL,
                                    () -> getCallsExtensiveApi(fromDateTime, toDateTimeFinal, callId, username, password))
                            .result();
                }
            }

            // Something else went wrong, so rethrow
            throw e;
        })
        .get();

        if (calls == null) {
            return List.of();
        }

        /*
         There is no way to filter by salesforce ID. So we instead get all the calls during the period,
         cache the result, and then filter the calls by the company ID.
         */
        return Arrays.stream(calls)
                .filter(Objects::nonNull)
                .filter(call -> call.getContext().stream()
                        .anyMatch(c ->
                                // The company can be blank
                                StringUtils.isBlank(company) ||
                                        // Or one of the call context object must be for Salesforce
                                        ("Salesforce".equals(c.system()) &&
                                                // And one of the objects must be an account that matches the company id
                                                c.getObjects().stream().anyMatch(o ->
                                                        company.equals(o.objectId()) && "Account".equals(o.objectType())))))
                .toList();

    }

    @Override
    public String getCallTranscript(
            final String username,
            final String password,
            final GongCallExtensive call) {
        final String callId = getCallId(call);
        if (StringUtils.isBlank(callId)) {
            logger.warning("Skipping Gong transcript retrieval because the call or metadata ID is missing");
            return "";
        }

        final GongCallTranscript transcript = Try.of(() -> localStorage.getOrPutObject(
                                GongClientLive.class.getSimpleName(),
                                "GongAPICallTranscript",
                                callId,
                                GongCallTranscript.class,
                                () -> getCallTranscriptApi(callId, username, password))
                        .result())
                .onFailure(NoSuchElementException.class, ex -> logger.warning("Transcript not found for Gong call " + callId))
                .getOrNull();

        if (transcript == null) {
            return "";
        }

        return Objects.requireNonNullElse(transcript.getTranscript(call), "");
    }

    @Nullable
    private String getCallId(@Nullable final GongCallExtensive call) {
        if (call == null || call.metaData() == null || StringUtils.isBlank(call.metaData().id())) {
            return null;
        }
        return call.metaData().id();
    }

    private GongCallExtensive[] getCallsExtensiveApi(
            @Nullable final String fromDateTime,
            @Nullable final String toDateTime,
            @Nullable final String callId,
            final String username,
            final String password) {
        return mutex.acquire(
                lockFile,
                () -> getCallsExtensiveApiLocked(fromDateTime, toDateTime, callId, username, password));
    }

    /**
     * https://gong.app.gong.io/settings/api/documentation#post-/v2/calls/extensive
     */
    private GongCallExtensive[] getCallsExtensiveApiLocked(
            @Nullable final String fromDateTime,
            @Nullable final String toDateTime,
            @Nullable final String callId,
            final String username,
            final String password) {

        final List<String> callIds = StringUtils.isBlank(callId) ? null : Arrays.stream(callId.split(",")).toList();
        final String nullableFromDateTime = StringUtils.isBlank(fromDateTime) ? null : fromDateTime;
        final String nullableToDateTime = StringUtils.isBlank(toDateTime) ? null : toDateTime;

        /*
         Use an iterative approach instead of recursion to avoid O(N²) memory from
         stacked ArrayUtils.addAll intermediate arrays and deep recursion stack frames.
         */
        final List<GongCallExtensive> result = new ArrayList<>();
        String currentCursor = "";
        boolean reachedMaxPages = true;

        for (int currentPage = 0; currentPage < MAX_PAGES; currentPage++) {
            logger.fine("Getting Gong calls extensive with IDs " + callId + " from " + fromDateTime + " to " + toDateTime + " with cursor " + currentCursor);

            final GongCallExtensiveQuery body = new GongCallExtensiveQuery(
                    new GongCallExtensiveQueryFiler(nullableFromDateTime, nullableToDateTime, null, callIds),
                    new GongCallExtensiveQueryContentSelector(
                            "Extended",
                            List.of("Now", "TimeOfCall"),
                            new GongCallExtensiveQueryExposedFields(true)),
                    currentCursor
            );

            // Cache at the level of each page of results. This reduces the size of each cached result.
            // CosmoDB especially has a limit on the size of each cached object.
            final String cacheKeyCursor = currentCursor;
            final GongCallsExtensive calls = localStorage.getOrPutObject(
                            GongClientLive.class.getSimpleName(),
                            "GongAPICallsExtensiveV2",
                            DigestUtils.sha256Hex(fromDateTime + toDateTime + callId + cacheKeyCursor),
                            TTL,
                            GongCallsExtensive.class,
                            () -> callApi(body, username, password))
                    .result();

            if (calls != null) {
                result.addAll(calls.getCalls());
            }

            if (calls == null || StringUtils.isBlank(calls.getRecords().getCursor())) {
                reachedMaxPages = false;
                break;
            }

            currentCursor = calls.getRecords().getCursor();
        }

        if (reachedMaxPages) {
            logger.warning("Reached maximum pages of " + MAX_PAGES + " when fetching Gong calls extensive");
        }

        return result.toArray(GongCallExtensive[]::new);
    }

    private GongCallsExtensive callApi(final GongCallExtensiveQuery body,
                                       final String username,
                                       final String password) {
        return Try.withResources(() -> new TimedOperation("Gong API call for calls extensive"))
                .of(t -> callApiTimed(body, username, password))
                .get();
    }

    private GongCallsExtensive callApiTimed(final GongCallExtensiveQuery body,
                                            final String username,
                                            final String password) {
        RATE_LIMITER.acquire();

        final String target = url + "/v2/calls/extensive";

        return httpClientCaller.call(
                clientConstructor::getClient,
                client -> client.target(target)
                        .request(MediaType.APPLICATION_JSON_TYPE)
                        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes()))
                        .post(Entity.entity(body, MediaType.APPLICATION_JSON)),
                response -> Try.of(() -> responseValidation.validate(response, target))
                        .map(r -> r.readEntity(GongCallsExtensive.class))
                        .get(),
                e -> new GongAPIException("Failed to get calls from Gong API: " + e.toString(), e));
    }


    private GongCallTranscript getCallTranscriptApi(
            final String id,
            final String username,
            final String password) {
        return mutex.acquire(
                lockFile,
                () -> getCallTranscriptApiLocked(id, username, password));
    }

    /**
     * https://gong.app.gong.io/settings/api/documentation#post-/v2/calls/transcript
     */
    private GongCallTranscript getCallTranscriptApiLocked(
            final String id,
            final String username,
            final String password) {
        logger.fine("Getting Gong call transcript from " + id);

        RATE_LIMITER.acquire();

        final String target = url + "/v2/calls/transcript";

        final GongCallTranscriptQuery body = new GongCallTranscriptQuery(
                new GongCallTranscriptQueryFilter(List.of(id))
        );

        return httpClientCaller.call(
                clientConstructor::getClient,
                client -> client.target(target)
                        .request()
                        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes()))
                        .header("Accept", MediaType.APPLICATION_JSON)
                        .post(Entity.entity(body, MediaType.APPLICATION_JSON)),
                response -> Try.of(() -> responseValidation.validate(response, target))
                        .map(r -> r.readEntity(GongCallTranscript.class))
                        .get(),
                cause -> new RuntimeException("Failed to get call transcript from Gong API", cause)
        );
    }
}
