package secondbrain.infrastructure.gong;

import com.google.common.util.concurrent.RateLimiter;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.httpclient.HttpClientCaller;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.mutex.Mutex;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.response.ResponseValidation;
import secondbrain.infrastructure.gong.api.*;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;

@ApplicationScoped
public class GongClientLive implements GongClient {
    private static final int TTL = 60 * 60 * 24 * 90;
    private static final RateLimiter RATE_LIMITER = RateLimiter.create(Constants.DEFAULT_RATE_LIMIT_PER_SECOND);
    private static final long API_CONNECTION_TIMEOUT_SECONDS_DEFAULT = 10;
    private static final long API_CALL_TIMEOUT_SECONDS_DEFAULT = 60 * 2; // 2 minutes
    private static final long CLIENT_TIMEOUT_BUFFER_SECONDS = 5;
    private static final long MUTEX_TIMEOUT_MS = 30 * 60 * 1000;
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

    private Client getClient() {
        final ClientBuilder clientBuilder = ClientBuilder.newBuilder();
        clientBuilder.connectTimeout(API_CONNECTION_TIMEOUT_SECONDS_DEFAULT, TimeUnit.SECONDS);
        // We want to use the timeoutService to handle timeouts, so we set the client timeout slightly longer.
        clientBuilder.readTimeout(API_CALL_TIMEOUT_SECONDS_DEFAULT + CLIENT_TIMEOUT_BUFFER_SECONDS, TimeUnit.SECONDS);
        return clientBuilder.build();
    }

    @Override
    public List<GongCallExtensive> getCallsExtensive(
            final String company,
            final String callId,
            final String username,
            final String password,
            final String fromDateTime,
            final String toDateTime) {

        // Build a "to" date for the cache key
        final String toDateTimeFinal = StringUtils.isBlank(toDateTime)
                ? OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS).format(ISO_OFFSET_DATE_TIME)
                : toDateTime;

        /*
         There is no way to filter by salesforce ID. So we instead get all the calls during the period,
         cache the result, and then filter the calls by the company ID.
         */
        final GongCallExtensive[] calls = getCallsExtensiveApiLocked(fromDateTime, toDateTimeFinal, callId, username, password, "", 0);

        if (calls == null) {
            return List.of();
        }

        return Arrays.stream(calls)
                .filter(call -> Objects.requireNonNullElse(call.context(), List.<GongCallExtensiveContext>of())
                        .stream()
                        .anyMatch(c ->
                                // The company can be blank
                                StringUtils.isBlank(company) ||
                                        // Or one of the call context object must be for Salesforce
                                        "Salesforce".equals(c.system()) &&
                                                // And one of the objects must be an account that matches the company id
                                                c.objects().stream().anyMatch(o ->
                                                        company.equals(o.objectId()) && "Account".equals(o.objectType()))))
                .toList();

    }

    @Override
    public String getCallTranscript(
            final String username,
            final String password,
            final GongCallExtensive call) {

        return localStorage.getOrPutObject(
                        GongClientLive.class.getSimpleName(),
                        "GongAPICallTranscript",
                        call.metaData().id(),
                        GongCallTranscript.class,
                        () -> getCallTranscriptApi(call.metaData().id(), username, password))
                .result()
                .getTranscript(call);
    }

    /**
     * https://gong.app.gong.io/settings/api/documentation#post-/v2/calls/extensive
     */
    private GongCallExtensive[] getCallsExtensiveApiLocked(
            final String fromDateTime,
            final String toDateTime,
            final String callId,
            final String username,
            final String password,
            final String cursor,
            final int page) {
        if (page >= MAX_PAGES) {
            logger.log(Level.WARNING, "Reached maximum pages of " + MAX_PAGES + " when fetching Gong calls extensive");
            return new GongCallExtensive[]{};
        }

        logger.fine("Getting Gong calls extensive with IDs " + callId + " from " + fromDateTime + " to " + toDateTime + " with cursor " + cursor);

        final List<String> callIds = StringUtils.isBlank(callId) ? null : Arrays.stream(callId.split(",")).toList();
        final String nullableFromDateTime = StringUtils.isBlank(fromDateTime) ? null : fromDateTime;
        final String nullableToDateTime = StringUtils.isBlank(toDateTime) ? null : toDateTime;

        final GongCallExtensiveQuery body = new GongCallExtensiveQuery(
                new GongCallExtensiveQueryFiler(nullableFromDateTime, nullableToDateTime, null, callIds),
                new GongCallExtensiveQueryContentSelector(
                        "Extended",
                        List.of("Now", "TimeOfCall"),
                        new GongCallExtensiveQueryExposedFields(true)),
                cursor
        );

        // Cache at the level of each page of results. This reduces the size of each cached result.
        // CosmoDB especially has a limit on the size of each cached object.
        final GongCallsExtensive calls = localStorage.getOrPutObject(
                        GongClientLive.class.getSimpleName(),
                        "GongAPICallsExtensiveV2",
                        DigestUtils.sha256Hex(fromDateTime + toDateTime + callId + cursor),
                        TTL,
                        GongCallsExtensive.class,
                        () -> callApi(body, username, password))
                .result();

        return ArrayUtils.addAll(
                calls.calls(),
                StringUtils.isNotBlank(calls.records().cursor())
                        ? getCallsExtensiveApiLocked(fromDateTime, toDateTime, callId, username, password, calls.records().cursor(), page + 1)
                        : new GongCallExtensive[]{});
    }

    private GongCallsExtensive callApi(final GongCallExtensiveQuery body,
                                       final String username,
                                       final String password) {
        return mutex.acquire(
                MUTEX_TIMEOUT_MS,
                lockFile + ".extensive",
                () -> callApiLocked(body, username, password));
    }

    private GongCallsExtensive callApiLocked(final GongCallExtensiveQuery body,
                                             final String username,
                                             final String password) {
        RATE_LIMITER.acquire();

        final String target = url + "/v2/calls/extensive";

        return httpClientCaller.call(
                this::getClient,
                client -> client.target(target)
                        .request(MediaType.APPLICATION_JSON_TYPE)
                        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes()))
                        .post(Entity.entity(body, MediaType.APPLICATION_JSON)),
                response -> Try.of(() -> responseValidation.validate(response, target))
                        .map(r -> r.readEntity(GongCallsExtensive.class))
                        .get(),
                e -> new RuntimeException("Failed to get calls from Gong API", e));
    }


    private GongCallTranscript getCallTranscriptApi(
            final String id,
            final String username,
            final String password) {
        return mutex.acquire(
                MUTEX_TIMEOUT_MS,
                lockFile + ".transcripts",
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
                this::getClient,
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
