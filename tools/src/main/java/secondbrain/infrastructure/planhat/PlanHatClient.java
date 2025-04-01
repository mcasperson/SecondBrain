package secondbrain.infrastructure.planhat;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.Retry;
import secondbrain.domain.concurrency.SemaphoreLender;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.response.ResponseValidation;

import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.List;

@ApplicationScoped
public class PlanHatClient {
    private static final SemaphoreLender SEMAPHORE_LENDER = new SemaphoreLender(Constants.DEFAULT_SEMAPHORE_COUNT);

    @Inject
    private ResponseValidation responseValidation;

    @Inject
    @ConfigProperty(name = "sb.planhat.url", defaultValue = "https://api-us4.planhat.com")
    private String url;

    @Inject
    private LocalStorage localStorage;

    public List<Conversation> getConversations(
            final Client client,
            final String company,
            final String token,
            final int ttlSeconds) {
        final Conversation[] conversations = localStorage.getOrPutObject(
                PlanHatClient.class.getSimpleName(),
                "PlanHatAPIConversations",
                DigestUtils.sha256Hex(company),
                ttlSeconds,
                Conversation[].class,
                () -> getConversationsApi(client, company, token));

        return conversations == null
                ? List.of()
                : List.of(conversations);
    }

    public Company getCompany(
            final Client client,
            final String company,
            final String token,
            final int ttlSeconds) {
        return localStorage.getOrPutObject(
                PlanHatClient.class.getSimpleName(),
                "PlanHatAPICompany",
                DigestUtils.sha256Hex(company + "V2"),
                ttlSeconds,
                Company.class,
                () -> getCompanyApi(client, company, token));
    }

    @Retry
    private Conversation[] getConversationsApi(final Client client, final String company, final String token) {
        final String target = url + "/conversations";

        // https://docs.planhat.com/#get_conversation_list
        final WebTarget webTarget = StringUtils.isNotBlank(company)
                ? client.target(target).queryParam("cId", company).queryParam("limit", 2000)
                : client.target(target).queryParam("limit", 2000);

        return Try.withResources(() -> SEMAPHORE_LENDER.lend(webTarget
                        .request()
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", MediaType.APPLICATION_JSON)
                        .get()))
                .of(response -> Try.of(() -> responseValidation.validate(response.getWrapped(), target))
                        .map(r -> r.readEntity(Conversation[].class))
                        .get())
                .get();
    }

    @Retry
    private Company getCompanyApi(final Client client, final String company, final String token) {
        final String target = url + "/companies/" + URLEncoder.encode(company, Charset.defaultCharset());

        return Try.withResources(() -> SEMAPHORE_LENDER.lend(client.target(target)
                        .request()
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", MediaType.APPLICATION_JSON)
                        .get()))
                .of(response -> Try.of(() -> responseValidation.validate(response.getWrapped(), target))
                        .map(r -> r.readEntity(Company.class))
                        .get())
                .get();
    }
}