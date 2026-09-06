package secondbrain.infrastructure.planhat;

import jakarta.ws.rs.client.Client;
import org.jspecify.annotations.Nullable;
import secondbrain.infrastructure.planhat.api.Company;
import secondbrain.infrastructure.planhat.api.Conversation;
import secondbrain.infrastructure.planhat.api.Email;
import secondbrain.infrastructure.planhat.api.Objective;
import secondbrain.infrastructure.planhat.api.Opportunity;
import secondbrain.infrastructure.planhat.api.PlanHatUser;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

public interface PlanHatClient {
    boolean anyItemsInDuration(
            Client client,
            String company,
            String url,
            String token,
            ChronoUnit duration,
            ChronoUnit cached);

    List<Conversation> getConversations(
            Client client,
            String company,
            String url,
            String token,
            @Nullable ZonedDateTime startDate,
            @Nullable ZonedDateTime endDate,
            int ttlSeconds);

    /**
     * Lists the emails that make up a conversation. The returned emails contain the summary details
     * only - use {@link #getEmail(Client, String, String, String, int)} to get the content of an
     * individual email.
     */
    List<Email> getConversationEmails(
            Client client,
            String conversationId,
            String url,
            String token,
            int ttlSeconds);

    /**
     * Gets the details, including the content, of an individual email.
     */
    Email getEmail(
            Client client,
            String emailId,
            String url,
            String token,
            int ttlSeconds);

    Company getCompany(
            Client client,
            String company,
            String url,
            String token,
            int ttlSeconds);

    List<Objective> getObjectives(
            Client client,
            String companyId,
            String url,
            String token,
            int ttlSeconds);

    PlanHatUser getUser(
            Client client,
            String userId,
            String url,
            String token,
            int ttlSeconds);

    List<Opportunity> getOpportunities(
            Client client,
            String companyId,
            String url,
            String token,
            int ttlSeconds);
}
