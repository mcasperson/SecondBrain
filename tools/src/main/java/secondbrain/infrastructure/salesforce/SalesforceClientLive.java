package secondbrain.infrastructure.salesforce;

import com.google.common.util.concurrent.RateLimiter;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.apache.commons.codec.digest.DigestUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.Timeout;
import secondbrain.domain.httpclient.TimeoutHttpClientCaller;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.response.ResponseValidation;
import secondbrain.infrastructure.planhat.PlanHatClientLive;
import secondbrain.infrastructure.salesforce.api.SalesforceOauthTokenResponse;
import secondbrain.infrastructure.salesforce.api.SalesforceTaskQuery;
import secondbrain.infrastructure.salesforce.api.SalesforceTaskRecord;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkState;

@ApplicationScoped
public class SalesforceClientLive implements SalesforceClient {
    private static final RateLimiter RATE_LIMITER = RateLimiter.create(Constants.DEFAULT_RATE_LIMIT_PER_SECOND);
    private static final int DEFAULT_CACHE_TTL_DAYS = 3;
    private static final long API_CONNECTION_TIMEOUT_SECONDS_DEFAULT = 10;
    private static final long API_CALL_TIMEOUT_SECONDS_DEFAULT = 60 * 10; // I've seen "Time to last byte" take at least 8 minutes, so we need a large buffer.
    private static final long API_CALL_DELAY_SECONDS_DEFAULT = 30;
    private static final long CLIENT_TIMEOUT_BUFFER_SECONDS = 5;
    private static final int API_RETRIES = 3;
    private static final String API_CALL_TIMEOUT_MESSAGE = "Call timed out after " + API_CALL_TIMEOUT_SECONDS_DEFAULT + " seconds";

    @Inject
    @ConfigProperty(name = "sb.salesforce.domain")
    private Optional<String> domain;

    @Inject
    @ConfigProperty(name = "sb.salesforce.apiversion", defaultValue = "v64.0")
    private String version;

    @Inject
    private ResponseValidation responseValidation;

    @Inject
    private LocalStorage localStorage;

    @Inject
    private Logger logger;

    @Inject
    private TimeoutHttpClientCaller httpClientCaller;

    private Client getClient() {
        final ClientBuilder clientBuilder = ClientBuilder.newBuilder();
        clientBuilder.connectTimeout(API_CONNECTION_TIMEOUT_SECONDS_DEFAULT, TimeUnit.SECONDS);
        // We want to use the timeoutService to handle timeouts, so we set the client timeout slightly longer.
        clientBuilder.readTimeout(API_CALL_TIMEOUT_SECONDS_DEFAULT + CLIENT_TIMEOUT_BUFFER_SECONDS, TimeUnit.SECONDS);
        return clientBuilder.build();
    }

    private String getUrl() {
        checkState(domain.isPresent(), "Salesforce domain is not configured");
        return "https://" + domain.get() + ".my.salesforce.com";
    }

    @Override
    public SalesforceOauthTokenResponse getToken(final String clientId, final String clientSecret) {
        checkState(domain.isPresent(), "Salesforce domain is not configured");

        return localStorage.getOrPutObject(
                        PlanHatClientLive.class.getSimpleName(),
                        "SalesforceAPIToken",
                        DigestUtils.sha256Hex(domain.get()),
                        60, // There is little harm in getting new tokens, but we'll cache for 1 minute to avoid spamming the API
                        SalesforceOauthTokenResponse.class,
                        () -> getTokenApi(clientId, clientSecret, 0))
                .result();
    }

    public SalesforceOauthTokenResponse getTokenApi(final String clientId, final String clientSecret, final int retryCount) {
        RATE_LIMITER.acquire();

        final String url = getUrl() + "/services/oauth2/token";

        final MultivaluedMap<String, String> body = new MultivaluedHashMap<>();
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("grant_type", "client_credentials");

        return Try.of(() -> httpClientCaller.call(
                        this::getClient,
                        client -> client.target(url)
                                .request(MediaType.APPLICATION_JSON_TYPE)
                                .post(Entity.entity(body, MediaType.APPLICATION_FORM_URLENCODED)),
                        response -> Try.of(() -> responseValidation.validate(response, url))
                                .map(r -> r.readEntity(SalesforceOauthTokenResponse.class))
                                .onFailure(e -> logger.severe(e.getMessage()))
                                .get(),
                        e -> new ExternalFailure("Failed to call the Salesforce API", e),
                        () -> {
                            throw new Timeout(API_CALL_TIMEOUT_MESSAGE);
                        },
                        API_CALL_TIMEOUT_SECONDS_DEFAULT,
                        API_CALL_DELAY_SECONDS_DEFAULT,
                        API_RETRIES))
                .get();
    }

    @Override
    public SalesforceTaskRecord[] getTasks(final String token, final String accountId, final String type) {
        checkState(domain.isPresent(), "Salesforce domain is not configured");

        return localStorage.getOrPutObject(
                        PlanHatClientLive.class.getSimpleName(),
                        "SalesforceAPITasks",
                        DigestUtils.sha256Hex(domain.get() + accountId + type),
                        DEFAULT_CACHE_TTL_DAYS * 24 * 60 * 60,
                        SalesforceTaskRecord[].class,
                        () -> getTasksApi(token, accountId, type, 0))
                .result();
    }

    private SalesforceTaskRecord[] getTasksApi(final String token, final String accountId, final String type, final int retryCount) {
        RATE_LIMITER.acquire();

        final String url = getUrl() + "/services/data/" + version + "/query";

        final String soql = "SELECT Id,Description,Subject,Type FROM Task WHERE AccountId='" + accountId + "' AND Type='" + type + "' ORDER BY ActivityDate DESC Limit 100";

        return Try.of(() -> httpClientCaller.call(
                        this::getClient,
                        client -> client.target(url)
                                .queryParam("q", soql)
                                .request()
                                .header("Authorization", "Bearer " + token)
                                .accept(MediaType.APPLICATION_JSON_TYPE)
                                .get(),
                        response -> Try.of(() -> responseValidation.validate(response, url))
                                .map(r -> r.readEntity(SalesforceTaskQuery.class))
                                .map(SalesforceTaskQuery::records)
                                .onFailure(e -> logger.severe(e.getMessage()))
                                .get(),
                        e -> new ExternalFailure("Failed to call the Salesforce API", e),
                        () -> {
                            throw new Timeout(API_CALL_TIMEOUT_MESSAGE);
                        },
                        API_CALL_TIMEOUT_SECONDS_DEFAULT,
                        API_CALL_DELAY_SECONDS_DEFAULT,
                        API_RETRIES))
                .get();
    }
}
