package secondbrain.infrastructure.planhat;

import com.google.common.util.concurrent.RateLimiter;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.faulttolerance.Retry;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.response.ResponseValidation;
import secondbrain.infrastructure.planhat.api.Company;
import secondbrain.infrastructure.planhat.api.Conversation;

import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.List;

@ApplicationScoped
public class PlanHatClientLive implements PlanHatClient {
    private static final RateLimiter RATE_LIMITER = RateLimiter.create(Constants.DEFAULT_RATE_LIMIT_PER_SECOND);

    @Inject
    private ResponseValidation responseValidation;

    @Inject
    private LocalStorage localStorage;

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
                () -> getConversationsApi(client, company, url, token));

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
                () -> getCompanyApi(client, company, url, token));
    }

    @Retry
    private Conversation[] getConversationsApi(final Client client, final String company, final String url, final String token) {
        RATE_LIMITER.acquire();

        final String target = url + "/conversations";

        // https://docs.planhat.com/#get_conversation_list
        final WebTarget webTarget = StringUtils.isNotBlank(company)
                ? client.target(target).queryParam("cId", company).queryParam("limit", 2000)
                : client.target(target).queryParam("limit", 2000);

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

    @Retry
    private Company getCompanyApi(final Client client, final String company, final String url, final String token) {
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