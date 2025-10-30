package secondbrain.domain.tools.salesforce.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.data.IdData;
import secondbrain.domain.data.TextData;
import secondbrain.domain.data.UrlData;
import secondbrain.domain.tooldefs.MetaObjectResult;
import secondbrain.domain.tooldefs.MetaObjectResults;
import secondbrain.infrastructure.salesforce.api.SalesforceTaskRecord;

import java.util.Objects;
import java.util.stream.Stream;

public record SalesforceTaskDetails(@JsonProperty("Id") String id,
                                    @JsonProperty("Description") String description,
                                    @JsonProperty("Type") String type,
                                    @JsonProperty("Subject") String subject,
                                    @JsonProperty("CreatedDate") String createdDate,
                                    String domain,
                                    @Nullable MetaObjectResult opportunityMeta1) implements IdData, TextData, UrlData {
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

    public SalesforceTaskDetails updateDomain(final String domain) {
        return new SalesforceTaskDetails(id, description, type, subject, createdDate, domain, opportunityMeta1);
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

    public MetaObjectResults getMetaObjectResults() {
        return new MetaObjectResults(Stream.of(opportunityMeta1).filter(Objects::nonNull).toList());
    }
}
