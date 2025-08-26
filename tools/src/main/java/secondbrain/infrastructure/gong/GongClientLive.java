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
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.response.ResponseValidation;
import secondbrain.domain.tools.gong.model.GongCallDetails;
import secondbrain.infrastructure.gong.api.*;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class GongClientLive implements GongClient {
    private static final int TTL = 60 * 60 * 24 * 90;
    private static final RateLimiter RATE_LIMITER = RateLimiter.create(Constants.DEFAULT_RATE_LIMIT_PER_SECOND);
    private static final long API_CONNECTION_TIMEOUT_SECONDS_DEFAULT = 10;
    private static final long API_CALL_TIMEOUT_SECONDS_DEFAULT = 60 * 2; // 2 minutes
    private static final long API_CALL_DELAY_SECONDS_DEFAULT = 30;
    private static final long CLIENT_TIMEOUT_BUFFER_SECONDS = 5;
    private static final String API_CALL_TIMEOUT_MESSAGE = "Call timed out after " + API_CALL_TIMEOUT_SECONDS_DEFAULT + " seconds";

    @Inject
    private ResponseValidation responseValidation;

    @Inject
    @ConfigProperty(name = "sb.gong.url", defaultValue = "https://api.gong.io")
    private String url;

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
    public List<GongCallDetails> getCallsExtensive(
            final String company,
            final String callId,
            final String username,
            final String password,
            final String fromDateTime,
            final String toDateTime) {

        /*
         There is no way to filter by salesforce ID. So we instead get all the calls during the period,
         cache the result, and then filter the calls by the company ID.
         */
        final GongCallExtensive[] calls = localStorage.getOrPutObject(
                        GongClientLive.class.getSimpleName(),
                        "GongAPICallsExtensive",
                        DigestUtils.sha256Hex(fromDateTime + toDateTime + callId),
                        TTL,
                        GongCallExtensive[].class,
                        () -> getCallsExtensiveApi(fromDateTime, toDateTime, callId, username, password, ""))
                .result();

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
                .map(gong -> new GongCallDetails(gong.metaData().id(), gong.metaData().url(), gong.parties()))
                .toList();

    }

    @Override
    public String getCallTranscript(
            final String username,
            final String password,
            final GongCallDetails call) {

        return localStorage.getOrPutObject(
                        GongClientLive.class.getSimpleName(),
                        "GongAPICallTranscript",
                        call.id(),
                        GongCallTranscript.class,
                        () -> getCallTranscriptApi(call.id(), username, password))
                .result()
                .getTranscript(call);
    }

    /**
     * https://gong.app.gong.io/settings/api/documentation#post-/v2/calls/extensive
     */
    private GongCallExtensive[] getCallsExtensiveApi(
            final String fromDateTime,
            final String toDateTime,
            final String callId,
            final String username,
            final String password,
            final String cursor) {
        logger.log(Level.INFO, "Getting Gong calls extensive from " + fromDateTime + " to " + toDateTime + " with cursor " + cursor);

        RATE_LIMITER.acquire();

        final String target = url + "/v2/calls/extensive";

        final List<String> callIds = StringUtils.isBlank(callId) ? null : List.of(callId);

        final GongCallExtensiveQuery body = new GongCallExtensiveQuery(
                new GongCallExtensiveQueryFiler(fromDateTime, toDateTime, null, callIds),
                new GongCallExtensiveQueryContentSelector(
                        "Extended",
                        List.of("Now", "TimeOfCall"),
                        new GongCallExtensiveQueryExposedFields(true)),
                cursor
        );

        return httpClientCaller.call(
                this::getClient,
                client -> client.target(target)
                        .request(MediaType.APPLICATION_JSON_TYPE)
                        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes()))
                        .post(Entity.entity(body, MediaType.APPLICATION_JSON)),
                response -> Try.of(() -> responseValidation.validate(response, target))
                        .map(r -> r.readEntity(GongCallsExtensive.class))
                        // Recurse if there is a next page, and we have not gone too far
                        .map(r -> ArrayUtils.addAll(
                                r.calls(),
                                StringUtils.isNotBlank(r.records().cursor())
                                        ? getCallsExtensiveApi(fromDateTime, toDateTime, callId, username, password, r.records().cursor())
                                        : new GongCallExtensive[]{}))
                        .get(),
                e -> new RuntimeException("Failed to get calls from Gong API", e));
    }

    /**
     * https://gong.app.gong.io/settings/api/documentation#post-/v2/calls/transcript
     */
    private GongCallTranscript getCallTranscriptApi(
            final String id,
            final String username,
            final String password) {
        logger.log(Level.INFO, "Getting Gong call transcript from " + id);

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
