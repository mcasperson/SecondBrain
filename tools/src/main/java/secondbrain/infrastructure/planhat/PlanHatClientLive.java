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
import secondbrain.domain.date.DateParser;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.mutex.Mutex;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.persist.TimedOperation;
import secondbrain.domain.response.ResponseValidation;
import secondbrain.infrastructure.planhat.api.Company;
import secondbrain.infrastructure.planhat.api.Conversation;

import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;

@ApplicationScoped
public class PlanHatClientLive implements PlanHatClient {
    private static final RateLimiter RATE_LIMITER = RateLimiter.create(5);
    private static final long MUTEX_TIMEOUT_MS = 30 * 60 * 1000;
    private static final int DEFAULT_PAGE_SIZE = 5;
    private static final int DEFAULT_MAX_OFFSET = 2000;
    private static final int MAX_LENGTH = 524288; // About 1MB for 2 byte characters.

    @Inject
    @ConfigProperty(name = "sb.planhat.lock", defaultValue = "sb_planhat.lock")
    private String lockFile;

    @Inject
    @ConfigProperty(name = "sb.planhat.pagesize", defaultValue = DEFAULT_PAGE_SIZE + "")
    private String pageSize;

    @Inject
    @ConfigProperty(name = "sb.planhat.maxoffset", defaultValue = DEFAULT_MAX_OFFSET + "")
    private String maxOffset;

    @Inject
    @ConfigProperty(name = "sb.planhat.maxlength", defaultValue = MAX_LENGTH + "")
    private String maxLength;

    @Inject
    private ResponseValidation responseValidation;

    @Inject
    @Preferred
    private LocalStorage localStorage;

    @Inject
    @Preferred
    private Mutex mutex;

    @Inject
    private DateParser dateParser;

    @Inject
    private Logger logger;

    @Override
    public List<Conversation> getConversations(
            final Client client,
            final String company,
            final String url,
            final String token,
            final ZonedDateTime startDate,
            final ZonedDateTime endDate,
            final int ttlSeconds) {
        final Conversation[] conversations = getConversationsApi(client, company, url, token, ttlSeconds, startDate, endDate, 0);

        // Do one last filter to ensure we only return conversations before the end date.
        // We don't do this earlier as we need to work all the way back to the start date,
        // and then trim any of the later results that are after the end date.
        return conversations == null
                ? List.of()
                : Stream.of(conversations)
                .filter(c -> dateParser.parseDate(c.date()).isBefore(endDate))
                .toList();
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
            final int ttlSeconds,
            final ZonedDateTime startDate,
            final ZonedDateTime endDate,
            final int offset) {
        if (offset >= getMaxOffset()) {
            logger.warning("Reached maximum offset of " + getMaxOffset() + " when fetching PlanHat conversations for company " + company);
            return new Conversation[]{};
        }

        logger.fine("Fetching PlanHat conversations for company " + company + " with offset " + offset);

        // We need to embed the current day in the cache key to ensure that we refresh the cache at least once per day.
        final String end = endDate.format(ISO_OFFSET_DATE_TIME);
        final String start = startDate.format(ISO_OFFSET_DATE_TIME);

        // Each conversation is cached individually because some local storage implementations
        // have limits on the size of each cached object.
        final Conversation[] conversations = localStorage.getOrPutObjectArray(
                        PlanHatClientLive.class.getSimpleName(),
                        "PlanHatAPIConversation",
                        DigestUtils.sha256Hex(company + url + start + end + offset),
                        ttlSeconds,
                        Conversation.class,
                        Conversation[].class,
                        () -> callApi(client, company, url, token, offset))
                .result();

        /*
         There is no way to select a date range with the Planhat API,
         so instead we keep recursing over the API until all the conversations
         are before the start date.
         */
        final Conversation[] filtered = Stream.of(conversations)
                .filter(c -> dateParser.parseDate(c.date()).isAfter(startDate))
                .toArray(Conversation[]::new);

        return ArrayUtils.addAll(
                filtered,
                filtered.length != 0
                        ? getConversationsApi(client, company, url, token, ttlSeconds, startDate, endDate, offset + getPageSize())
                        : new Conversation[]{});
    }

    private Conversation[] callApi(final Client client, final String company, final String url, final String token, final int offset) {
        return mutex.acquire(
                MUTEX_TIMEOUT_MS,
                lockFile,
                () -> callApiLocked(client, company, url, token, offset));
    }

    private Conversation[] callApiLocked(final Client client, final String company, final String url, final String token, final int offset) {
        return Try.withResources(() -> new TimedOperation("Planhat API call for conversations"))
                .of(t -> callApiTimed(client, company, url, token, offset))
                .get();
    }

    private Conversation[] callApiTimed(final Client client, final String company, final String url, final String token, final int offset) {
        logger.fine("Calling PlanHat Conversations API for company " + company + " with offset " + offset);

        RATE_LIMITER.acquire();

        final String target = url + "/conversations";

        // https://docs.planhat.com/#get_conversation_list
        final WebTarget webTarget = StringUtils.isNotBlank(company)
                ? client.target(target).queryParam("cId", company).queryParam("limit", getPageSize()).queryParam("offset", offset)
                : client.target(target).queryParam("limit", getPageSize()).queryParam("offset", offset);

        final Conversation[] result = Try.withResources(() -> webTarget
                        .request()
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", MediaType.APPLICATION_JSON)
                        .get())
                .of(response -> Try.of(() -> responseValidation.validate(response, target))
                        .map(r -> r.readEntity(Conversation[].class))
                        .get())
                .get();

        // Trim description and snippet to max length if needed.
        if (getMaxLength() > 0) {
            return Stream.of(result)
                    .map(c -> c.updateDescriptionAndSnippet(
                            StringUtils.substring(c.description(), 0, getMaxLength()),
                            StringUtils.substring(c.snippet(), 0, getMaxLength())))
                    .toArray(Conversation[]::new);
        }

        return result;
    }

    private Company getCompanyApi(
            final Client client,
            final String company,
            final String url,
            final String token) {
        return mutex.acquire(
                MUTEX_TIMEOUT_MS,
                lockFile,
                () -> getCompanyApiLocked(client, company, url, token));
    }

    private Company getCompanyApiLocked(
            final Client client,
            final String company,
            final String url,
            final String token) {
        logger.fine("Calling PlanHat Company API for company " + company);

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

    private int getPageSize() {
        return Try.of(() -> Integer.parseInt(pageSize))
                .filter(size -> size > 0)
                .getOrElse(() -> DEFAULT_PAGE_SIZE);
    }

    private int getMaxOffset() {
        return Try.of(() -> Integer.parseInt(maxOffset))
                .filter(size -> size > 0)
                .getOrElse(() -> DEFAULT_MAX_OFFSET);
    }

    private int getMaxLength() {
        return Try.of(() -> Integer.parseInt(maxLength))
                .filter(size -> size > 0)
                .getOrElse(() -> MAX_LENGTH);
    }
}