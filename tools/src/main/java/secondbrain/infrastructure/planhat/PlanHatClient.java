package secondbrain.infrastructure.planhat;

import jakarta.ws.rs.client.Client;
import org.jspecify.annotations.Nullable;
import secondbrain.infrastructure.planhat.api.Company;
import secondbrain.infrastructure.planhat.api.Conversation;
import secondbrain.infrastructure.planhat.api.Objective;

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
}
