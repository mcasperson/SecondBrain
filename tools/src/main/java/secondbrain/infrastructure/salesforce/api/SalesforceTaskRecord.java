package secondbrain.infrastructure.salesforce.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SalesforceTaskRecord(String id, String description, String type, String subject) {
    public String getEmailText() {
        return subject + "\n" + description;
    }
}
