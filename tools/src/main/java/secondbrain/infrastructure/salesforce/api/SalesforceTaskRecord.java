package secondbrain.infrastructure.salesforce.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SalesforceTaskRecord(@JsonProperty("Id") String id,
                                   @JsonProperty("Description") String description,
                                   @JsonProperty("Type") String type,
                                   @JsonProperty("Subject") String subject,
                                   @JsonProperty("CreatedDate") String createdDate,
                                   String domain) {

    public String getDescription() {
        return description == null ? "" : description;
    }

    public String getType() {
        return type == null ? "" : type;
    }

    public String getSubject() {
        return subject == null ? "" : subject;
    }

    public String getCreatedDate() {
        return createdDate == null ? "" : createdDate;
    }

    public SalesforceTaskRecord updateDomain(final String domain) {
        return new SalesforceTaskRecord(id, description, type, subject, createdDate, domain);
    }

    public String getId() {
        return id;
    }
}
