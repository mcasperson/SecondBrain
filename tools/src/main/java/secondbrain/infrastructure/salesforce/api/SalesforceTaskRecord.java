package secondbrain.infrastructure.salesforce.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import secondbrain.domain.data.IdData;
import secondbrain.domain.data.TextData;
import secondbrain.domain.data.UrlData;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SalesforceTaskRecord(@JsonProperty("Id") String id,
                                   @JsonProperty("Description") String description,
                                   @JsonProperty("Type") String type,
                                   @JsonProperty("Subject") String subject,
                                   @JsonProperty("CreatedDate") String createdDate,
                                   String domain) implements IdData, TextData, UrlData {

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

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getText() {
        return getSubject() + " " + getCreatedDate() + "\n" + getDescription();
    }

    @Override
    public String getLinkText() {
        return "Salesforce Task " + getId();
    }

    @Override
    public String getUrl() {
        return "https://" + (domain == null ? "fixme" : domain) + ".lightning.force.com/lightning/r/Task/" + getId() + "/view";
    }

    public SalesforceTaskRecord updateDescription(final String newDescription) {
        return new SalesforceTaskRecord(id, newDescription, type, subject, createdDate, domain);
    }
}
