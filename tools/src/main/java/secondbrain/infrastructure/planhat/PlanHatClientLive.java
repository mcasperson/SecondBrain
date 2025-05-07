package secondbrain.infrastructure.planhat;

import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.faulttolerance.Retry;
import secondbrain.domain.concurrency.SemaphoreLender;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.exceptions.InvalidResponse;
import secondbrain.domain.exceptions.MissingResponse;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.response.ResponseValidation;
import secondbrain.infrastructure.planhat.api.Company;
import secondbrain.infrastructure.planhat.api.Conversation;

import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.List;

import static com.google.common.base.Predicates.instanceOf;

@ApplicationScoped
public class PlanHatClientLive implements PlanHatClient {
    private static final SemaphoreLender SEMAPHORE_LENDER = new SemaphoreLender(Constants.DEFAULT_SEMAPHORE_COUNT);

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
                DigestUtils.sha256Hex(company),
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
                DigestUtils.sha256Hex(company + "V2"),
                ttlSeconds,
                Company.class,
                () -> getCompanyApi(client, company, url, token));
    }

    @Retry
    private Conversation[] getConversationsApi(final Client client, final String company, final String url, final String token) {
        final String target = url + "/conversations";

        // https://docs.planhat.com/#get_conversation_list
        final WebTarget webTarget = StringUtils.isNotBlank(company)
                ? client.target(target).queryParam("cId", company).queryParam("limit", 2000)
                : client.target(target).queryParam("limit", 2000);

        final Try<Conversation[]> result = Try.withResources(() -> SEMAPHORE_LENDER.lend(webTarget
                        .request()
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", MediaType.APPLICATION_JSON)
                        .get()))
                .of(response -> Try.of(() -> responseValidation.validate(response.getWrapped(), target))
                        .map(r -> r.readEntity(Conversation[].class))
                        .get());

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                        API.Case(API.$(instanceOf(MissingResponse.class)), throwable -> new InternalFailure(throwable)),
                        API.Case(API.$(instanceOf(InvalidResponse.class)), throwable -> new ExternalFailure(throwable)),
                        API.Case(API.$(instanceOf(InternalFailure.class)), throwable -> throwable),
                        API.Case(API.$(), ex -> new ExternalFailure(PlanHatClientLive.class.getSimpleName() + " failed to call PlanHat API", ex)))
                .get();
    }

    @Retry
    private Company getCompanyApi(final Client client, final String company, final String url, final String token) {
        final String target = url + "/companies/" + URLEncoder.encode(company, Charset.defaultCharset());

        final Try<Company> result = Try.withResources(() -> SEMAPHORE_LENDER.lend(client.target(target)
                        .request()
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", MediaType.APPLICATION_JSON)
                        .get()))
                .of(response -> Try.of(() -> responseValidation.validate(response.getWrapped(), target))
                        .map(r -> r.readEntity(Company.class))
                        .get());

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                        API.Case(API.$(instanceOf(MissingResponse.class)), throwable -> new InternalFailure(throwable)),
                        API.Case(API.$(instanceOf(InvalidResponse.class)), throwable -> new ExternalFailure(throwable)),
                        API.Case(API.$(instanceOf(InternalFailure.class)), throwable -> throwable),
                        API.Case(API.$(), ex -> new ExternalFailure(PlanHatClientLive.class.getSimpleName() + " failed to call PlanHat API", ex)))
                .get();
    }
}