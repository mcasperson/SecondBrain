package secondbrain.domain.test;

import jakarta.ws.rs.client.Client;
import org.jspecify.annotations.Nullable;
import secondbrain.infrastructure.planhat.PlanHatClient;
import secondbrain.infrastructure.planhat.api.Company;
import secondbrain.infrastructure.planhat.api.Conversation;
import secondbrain.infrastructure.planhat.api.Email;
import secondbrain.infrastructure.planhat.api.Objective;
import secondbrain.infrastructure.planhat.api.Opportunity;
import secondbrain.infrastructure.planhat.api.PlanHatUser;
import secondbrain.infrastructure.planhat.api.TicketPart;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A PlanHat client that returns the configured objectives and opportunities. Each call is recorded, and calls can be
 * made to fail. Methods that are not supported throw UnsupportedOperationException. Expose it with a
 * {@code @Produces @Preferred} method.
 */
public final class FakePlanHatClient implements PlanHatClient {
    /**
     * A recorded call to getObjectives or getOpportunities.
     */
    public record Call(String method, Client client, String companyId, String url, String token, int ttlSeconds) {
    }

    public final List<Objective> objectives = new ArrayList<>();
    public final List<Opportunity> opportunities = new ArrayList<>();
    public final List<Call> calls = new CopyOnWriteArrayList<>();
    public volatile boolean fail;

    private void record(final String method, final Client client, final String companyId, final String url, final String token, final int ttlSeconds) {
        calls.add(new Call(method, client, companyId, url, token, ttlSeconds));
        if (fail) {
            throw new IllegalStateException("PlanHat unavailable");
        }
    }

    @Override
    public List<Objective> getObjectives(final Client client, final String companyId, final String url, final String token, final int ttlSeconds) {
        record("getObjectives", client, companyId, url, token, ttlSeconds);
        return objectives;
    }

    @Override
    public List<Opportunity> getOpportunities(final Client client, final String companyId, final String url, final String token, final int ttlSeconds) {
        record("getOpportunities", client, companyId, url, token, ttlSeconds);
        return opportunities;
    }

    @Override
    public boolean anyItemsInDuration(final Client client, final String company, final String url, final String token, final ChronoUnit duration, final ChronoUnit cached) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Conversation> getConversations(final Client client, final String company, final String url, final String token, @Nullable final ZonedDateTime startDate, @Nullable final ZonedDateTime endDate, final int ttlSeconds) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Email> getConversationEmails(final Client client, final String conversationId, final String url, final String token, final int ttlSeconds) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Email getEmail(final Client client, final String emailId, final String url, final String token, final int ttlSeconds) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<TicketPart> getTicketParts(final Client client, final String ticketId, final String url, final String token, final int ttlSeconds) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Company getCompany(final Client client, final String company, final String url, final String token, final int ttlSeconds) {
        throw new UnsupportedOperationException();
    }

    @Override
    public PlanHatUser getUser(final Client client, final String userId, final String url, final String token, final int ttlSeconds) {
        throw new UnsupportedOperationException();
    }
}
