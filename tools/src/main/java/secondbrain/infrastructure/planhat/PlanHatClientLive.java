package secondbrain.infrastructure.planhat;

import com.google.common.util.concurrent.RateLimiter;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.mutex.Mutex;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.response.ResponseValidation;
import secondbrain.infrastructure.planhat.api.Company;
import secondbrain.infrastructure.planhat.api.Conversation;

import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;

@ApplicationScoped
public class PlanHatClientLive implements PlanHatClient {
    private static final RateLimiter RATE_LIMITER = RateLimiter.create(2);
    private static final long MUTEX_TIMEOUT_MS = 30 * 60 * 1000;
    private static final int PAGE_SIZE = 10;

    @Inject
    @ConfigProperty(name = "sb.planhat.lock", defaultValue = "sb_planhat.lock")
    private String lockFile;

    @Inject
    private ResponseValidation responseValidation;

    @Inject
    @Preferred
    private LocalStorage localStorage;

    @Inject
    private Mutex mutex;

    @Override
    public List<Conversation> getConversations(
            final Client client,
            final String company,
            final String url,
            final String token,
            final int ttlSeconds) {
        final Conversation[] conversations = localStorage.getOrPutObject(
                        PlanHatClientLive.class.getSimpleName(),
                        "PlanHatAPIConversations",
                        DigestUtils.sha256Hex(company + url),
                        ttlSeconds,
                        Conversation[].class,
                        () -> getConversationsApi(client, company, url, token, ttlSeconds))
                .result();

        return conversations == null
                ? List.of()
                : List.of(conversations);
    }

    @Override
    public Company getCompany(
            final Client client,
            final String company,
            final String url,
            final String token,
            final int ttlSeconds) {
        return localStorage.getOrPutObject(
                        PlanHatClientLive.class.getSimpleName(),
                        "PlanHatAPICompany",
                        DigestUtils.sha256Hex(company + url),
                        ttlSeconds,
                        Company.class,
                        () -> getCompanyApi(client, company, url, token))
                .result();
    }

    private Conversation[] getConversationsApi(
            final Client client,
            final String company,
            final String url,
            final String token,
            final int ttlSeconds) {
        return mutex.acquire(
                MUTEX_TIMEOUT_MS,
                lockFile + ".conversations",
                () -> getConversationsApiLocked(client, company, url, token, ttlSeconds, 0));
    }

    private Conversation[] getConversationsApiLocked(final Client client, final String company, final String url, final String token, final int ttlSeconds, final int offset) {
        // We need to embed the current day in the cache key to ensure that we refresh the cache at least once per day.
        final String today = OffsetDateTime.now(ZoneId.systemDefault())
                .truncatedTo(ChronoUnit.DAYS)
                .format(ISO_OFFSET_DATE_TIME);

        final Conversation[] conversations = localStorage.getOrPutObject(
                        PlanHatClientLive.class.getSimpleName(),
                        "PlanHatAPICompany",
                        DigestUtils.sha256Hex(company + url + today + offset),
                        ttlSeconds,
                        Conversation[].class,
                        () -> callApi(client, company, url, token, offset))
                .result();

        return ArrayUtils.addAll(
                conversations,
                conversations.length != 0
                        ? getConversationsApiLocked(client, company, url, token, ttlSeconds, offset + PAGE_SIZE)
                        : new Conversation[]{});
    }

    private Conversation[] callApi(final Client client, final String company, final String url, final String token, final int offset) {
        RATE_LIMITER.acquire();

        final String target = url + "/conversations";

        // https://docs.planhat.com/#get_conversation_list
        final WebTarget webTarget = StringUtils.isNotBlank(company)
                ? client.target(target).queryParam("cId", company).queryParam("limit", PAGE_SIZE).queryParam("offset", offset)
                : client.target(target).queryParam("limit", PAGE_SIZE).queryParam("offset", offset);

        return Try.withResources(() -> webTarget
                        .request()
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", MediaType.APPLICATION_JSON)
                        .get())
                .of(response -> Try.of(() -> responseValidation.validate(response, target))
                        .map(r -> r.readEntity(Conversation[].class))
                        .get())
                .get();
    }

    private Company getCompanyApi(
            final Client client,
            final String company,
            final String url,
            final String token) {
        return mutex.acquire(
                MUTEX_TIMEOUT_MS,
                lockFile + ".company",
                () -> getCompanyApiLocked(client, company, url, token));
    }

    private Company getCompanyApiLocked(
            final Client client,
            final String company,
            final String url,
            final String token) {
        RATE_LIMITER.acquire();

        final String target = url + "/companies/" + URLEncoder.encode(company, Charset.defaultCharset());

        return Try.withResources(() -> client.target(target)
                        .request()
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", MediaType.APPLICATION_JSON)
                        .get())
                .of(response -> Try.of(() -> responseValidation.validate(response, target))
                        .map(r -> r.readEntity(Company.class))
                        .get())
                .get();
    }
}