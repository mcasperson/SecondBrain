package secondbrain.infrastructure.planhat;

import jakarta.ws.rs.client.Client;
import secondbrain.infrastructure.planhat.api.Company;
import secondbrain.infrastructure.planhat.api.Conversation;

import java.time.ZonedDateTime;
import java.util.List;

public interface PlanHatClient {
    List<Conversation> getConversations(
            Client client,
            String company,
            String url,
            String token,
            ZonedDateTime startDate,
            int ttlSeconds);

    Company getCompany(
            Client client,
            String company,
            String url,
            String token,
            int ttlSeconds);
}
