package secondbrain.infrastructure.planhat;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.injection.Preferred;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.planhat.api.Company;
import secondbrain.infrastructure.planhat.api.Conversation;
import secondbrain.infrastructure.planhat.api.Objective;
import secondbrain.infrastructure.planhat.api.Opportunity;
import secondbrain.infrastructure.planhat.api.PlanHatUser;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class PlanHatClientMock implements PlanHatClient {
    @Inject
    @Preferred
    private LlmClient llmClient;

    @Override
    public boolean anyItemsInDuration(
            final Client client,
            final String company,
            final String url,
            final String token,
            final ChronoUnit duration,
            final ChronoUnit cached) {
        return true;
    }

    @Override
    public List<Conversation> getConversations(final Client client, final String company, final String url, final String token, @Nullable final ZonedDateTime startDate, @Nullable final ZonedDateTime endDate, final int ttlSeconds) {
        // Create a list of mock conversations
        return List.of(
                createMockConversation(),
                createMockConversation(),
                createMockConversation()
        );
    }

    @Override
    public Company getCompany(final Client client, final String company, final String url, final String token, final int ttlSeconds) {
        // Generate a mock company
        String companyId = UUID.randomUUID().toString();
        String companyName = llmClient.call("Generate a company name. Return only the name, nothing else.", Map.of());

        // Create mock usage data
        Map<String, Integer> usage = new HashMap<>();
        usage.put("apiCalls", (int) (Math.random() * 1000));
        usage.put("storageUsed", (int) (Math.random() * 5000));

        // Create mock custom fields
        Map<String, Object> custom = new HashMap<>();
        custom.put("industry", llmClient.call("Generate a company industry. Return only the industry name, nothing else.", Map.of()));
        custom.put("contactPerson", llmClient.call("Generate a person's name. Return only the name, nothing else.", Map.of()));
        custom.put("tier", llmClient.call("Generate one of: Free, Standard, Premium, Enterprise. Return only the tier, nothing else.", Map.of()));

        return new Company(companyId, companyName, null, 10, usage, custom);
    }

    @Override
    public List<Objective> getObjectives(final Client client, final String companyId, final String url, final String token, final int ttlSeconds) {
        return List.of(
                new Objective(
                        UUID.randomUUID().toString(),
                        companyId,
                        llmClient.call("Generate a company name. Return only the name, nothing else.", Map.of()),
                        llmClient.call("Generate a short business objective name. Return only the name, nothing else.", Map.of()),
                        (int) (Math.random() * 10),
                        false,
                        Map.of("Use Case Status", "In Progress"),
                        ZonedDateTime.now(ZoneOffset.UTC).minusDays(30).format(DateTimeFormatter.ISO_INSTANT),
                        ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)));
    }

    @Override
    public PlanHatUser getUser(final Client client, final String userId, final String url, final String token, final int ttlSeconds) {
        final String firstName = llmClient.call("Generate a first name. Return only the first name, nothing else.", Map.of());
        final String lastName = llmClient.call("Generate a last name. Return only the last name, nothing else.", Map.of());
        return new PlanHatUser(userId, firstName, lastName);
    }

    @Override
    public List<Opportunity> getOpportunities(final Client client, final String companyId, final String url, final String token, final int ttlSeconds) {
        final String status = List.of("active", "won", "lost").get((int) (Math.random() * 3));
        final String title = llmClient.call("Generate a short sales opportunity title. Return only the title, nothing else.", Map.of());
        return List.of(
                new Opportunity(
                        UUID.randomUUID().toString(),
                        status,
                        companyId,
                        llmClient.call("Generate a company name. Return only the name, nothing else.", Map.of()),
                        title,
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        Math.random() * 10000,
                        Math.random() * 120000,
                        status.equals("active") ? "New" : status.equals("won") ? "Closed Won" : "Closed Lost",
                        ZonedDateTime.now(ZoneOffset.UTC).minusDays(30).format(DateTimeFormatter.ISO_INSTANT),
                        status.equals("active") ? null : ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT),
                        ZonedDateTime.now(ZoneOffset.UTC).minusDays(60).format(DateTimeFormatter.ISO_INSTANT),
                        ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT),
                        Map.of("Opportunity Name", title)));
    }

    private Conversation createMockConversation() {
        String id = UUID.randomUUID().toString();
        String description = llmClient.call("Generate a paragraph describing a customer conversation. Make it concise and professional.", Map.of());
        String snippet = llmClient.call("Generate a brief one-sentence summary of a customer conversation.", Map.of());
        String date = ZonedDateTime.now(ZoneOffset.UTC).minusDays((long) (Math.random() * 30))
                .format(DateTimeFormatter.ISO_INSTANT);
        String companyId = UUID.randomUUID().toString();
        String companyName = llmClient.call("Generate a company name. Return only the name, nothing else.", Map.of());
        String subject = llmClient.call("Generate a subject line for a customer conversation email. Keep it brief.", Map.of());
        String type = List.of("email", "call", "meeting", "chat").get((int) (Math.random() * 4));

        return new Conversation(id, description, snippet, date, companyId, companyName, subject, type, "");
    }
}
