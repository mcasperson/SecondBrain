package secondbrain.infrastructure.salesforce.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SalesforceTaskRecord(@JsonProperty("Id") String id, @JsonProperty("Description") String description,
                                   @JsonProperty("Type") String type, @JsonProperty("Subject") String subject,
                                   @JsonProperty("CreatedDate") String createdDate) {
    public String getEmailText() {
        return subject + " " + createdDate + "\n" + description;
    }
}
