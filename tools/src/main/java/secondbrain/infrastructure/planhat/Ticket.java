package secondbrain.infrastructure.planhat;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Ticket(@JsonProperty("_id") String id, String body, String createdDate) {
}
