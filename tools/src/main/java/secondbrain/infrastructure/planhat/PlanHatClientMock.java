package secondbrain.infrastructure.planhat;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.planhat.api.Company;
import secondbrain.infrastructure.planhat.api.Conversation;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class PlanHatClientMock implements PlanHatClient {
    @Inject
    private OllamaClient ollamaClient;

    @Override
    public List<Conversation> getConversations(Client client, String company, String url, String token, int ttlSeconds) {
        // Create a list of mock conversations
        return List.of(
                createMockConversation(),
                createMockConversation(),
                createMockConversation()
        );
    }

    @Override
    public Company getCompany(Client client, String company, String url, String token, int ttlSeconds) {
        // Generate a mock company
        String companyId = UUID.randomUUID().toString();
        String companyName = ollamaClient.callOllamaSimple("Generate a company name. Return only the name, nothing else.");

        // Create mock usage data
        Map<String, Integer> usage = new HashMap<>();
        usage.put("apiCalls", (int) (Math.random() * 1000));
        usage.put("storageUsed", (int) (Math.random() * 5000));

        // Create mock custom fields
        Map<String, Object> custom = new HashMap<>();
        custom.put("industry", ollamaClient.callOllamaSimple("Generate a company industry. Return only the industry name, nothing else."));
        custom.put("contactPerson", ollamaClient.callOllamaSimple("Generate a person's name. Return only the name, nothing else."));
        custom.put("tier", ollamaClient.callOllamaSimple("Generate one of: Free, Standard, Premium, Enterprise. Return only the tier, nothing else."));

        return new Company(companyId, companyName, usage, custom);
    }

    private Conversation createMockConversation() {
        String id = UUID.randomUUID().toString();
        String description = ollamaClient.callOllamaSimple("Generate a paragraph describing a customer conversation. Make it concise and professional.");
        String snippet = ollamaClient.callOllamaSimple("Generate a brief one-sentence summary of a customer conversation.");
        String date = ZonedDateTime.now().minusDays((long) (Math.random() * 30))
                .format(DateTimeFormatter.ISO_INSTANT);
        String companyId = UUID.randomUUID().toString();
        String companyName = ollamaClient.callOllamaSimple("Generate a company name. Return only the name, nothing else.");
        String subject = ollamaClient.callOllamaSimple("Generate a subject line for a customer conversation email. Keep it brief.");
        String type = List.of("email", "call", "meeting", "chat").get((int) (Math.random() * 4));

        return new Conversation(id, description, snippet, date, companyId, companyName, subject, type);
    }
}
