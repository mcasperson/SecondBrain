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
import org.apache.commons.lang.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.InvalidResponse;
import secondbrain.domain.exceptions.Timeout;
import secondbrain.domain.httpclient.TimeoutHttpClientCaller;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.mutex.Mutex;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.response.ResponseValidation;
import secondbrain.infrastructure.salesforce.api.SalesforceOauthTokenResponse;
import secondbrain.infrastructure.salesforce.api.SalesforceTaskQuery;
import secondbrain.infrastructure.salesforce.api.SalesforceTaskRecord;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkState;

@ApplicationScoped
public class SalesforceClientLive implements SalesforceClient {
    private static final RateLimiter RATE_LIMITER = RateLimiter.create(4);
    private static final int DEFAULT_CACHE_TTL_DAYS = 3;
    private static final long API_CONNECTION_TIMEOUT_SECONDS_DEFAULT = 10;
    private static final long API_CALL_TIMEOUT_SECONDS_DEFAULT = 60 * 10; // I've seen "Time to last byte" take at least 8 minutes, so we need a large buffer.
    private static final long API_CALL_DELAY_SECONDS_DEFAULT = 30;
    private static final long CLIENT_TIMEOUT_BUFFER_SECONDS = 5;
    private static final int API_RETRIES = 3;
    private static final long MUTEX_TIMEOUT_MS = 30 * 60 * 1000;
    private static final String API_CALL_TIMEOUT_MESSAGE = "Call timed out after " + API_CALL_TIMEOUT_SECONDS_DEFAULT + " seconds";

    @Inject
    @ConfigProperty(name = "sb.salesforce.lock", defaultValue = "sb_salesforce.lock")
    private String lockFile;

    @Inject
    @ConfigProperty(name = "sb.salesforce.domain")
    private Optional<String> domain;

    @Inject
    @ConfigProperty(name = "sb.salesforce.apiversion", defaultValue = "v64.0")
    private String version;

    @Inject
    private ResponseValidation responseValidation;

    @Inject
    @Preferred
    private LocalStorage localStorage;

    @Inject
    private Logger logger;

    @Inject
    private TimeoutHttpClientCaller httpClientCaller;

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

    private String getUrl() {
        checkState(domain.isPresent(), "Salesforce domain is not configured");
        return "https://" + domain.get() + ".my.salesforce.com";
    }

    @Override
    public SalesforceOauthTokenResponse getToken(final String clientId, final String clientSecret) {
        checkState(domain.isPresent(), "Salesforce domain is not configured");

        return localStorage.getOrPutObject(
                        SalesforceClientLive.class.getSimpleName(),
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
    public SalesforceTaskRecord[] getTasks(final String token, final String accountId, final String type, final String startDate, final String endDate) {
        checkState(domain.isPresent(), "Salesforce domain is not configured");

        return localStorage.getOrPutObject(
                        SalesforceClientLive.class.getSimpleName(),
                        "SalesforceAPITasks",
                        DigestUtils.sha256Hex(domain.get() + accountId + type + startDate + endDate),
                        DEFAULT_CACHE_TTL_DAYS * 24 * 60 * 60,
                        SalesforceTaskRecord[].class,
                        () -> getTasksApi(token, accountId, type, startDate, endDate))
                .result();
    }

    private SalesforceTaskRecord[] getTasksApi(final String token, final String accountId, final String type, final String startDate, final String endDate) {
        return mutex.acquire(
                MUTEX_TIMEOUT_MS,
                lockFile + ".tasks",
                () -> getTasksApiLocked(token, accountId, type, startDate, endDate, 0));
    }

    private SalesforceTaskRecord[] getTasksApiLocked(final String token, final String accountId, final String type, final String startDate, final String endDate, final int retryCount) {
        logger.fine("Getting Salesforce tasks for account " + accountId + " from " + startDate + " to " + endDate);

        if (retryCount > API_RETRIES) {
            throw new ExternalFailure("Exceeded maximum retries calling Salesforce API");
        }

        RATE_LIMITER.acquire();

        final String url = getUrl() + "/services/data/" + version + "/query";

        final StringBuffer soql = new StringBuffer();
        soql.append("SELECT Id,Description,Subject,Type,CreatedDate FROM Task WHERE AccountId='")
                .append(accountId)
                .append("' AND Type='")
                .append(type)
                .append("'");
        if (StringUtils.isNotBlank(startDate)) {
            soql.append(" AND ActivityDate>=")
                    .append(startDate);
        }
        if (StringUtils.isNotBlank(endDate)) {
            soql.append(" AND ActivityDate<=")
                    .append(endDate);
        }
        soql.append(" ORDER BY ActivityDate DESC Limit 100");

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
                .recover(InvalidResponse.class, ex -> {
                    if (ex.getCode() == 429) {
                        Try.run(() -> Thread.sleep(API_CALL_DELAY_SECONDS_DEFAULT * 1000));
                        return getTasksApiLocked(token, accountId, type, startDate, endDate, retryCount + 1);
                    }

                    throw new ExternalFailure("Could not call salesforce query", ex);
                })
                .onFailure(e -> logger.severe("Failed to get tasks for salesforce account " + accountId + "\n" + e.getMessage()))
                .onSuccess(records -> logger.fine("Retrieved " + (records != null ? records.length : 0) + " tasks from Salesforce for account " + accountId))
                .get();
    }
}
