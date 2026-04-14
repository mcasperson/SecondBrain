package secondbrain.infrastructure.salesforce.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import secondbrain.domain.data.IdData;
import secondbrain.domain.data.TextData;
import secondbrain.domain.data.UrlData;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SalesforceEmailRecord(@JsonProperty("Id") String id,
                                    @JsonProperty("Subject") String subject,
                                    @JsonProperty("TextBody") String textBody,
                                    @JsonProperty("MessageDate") String messageDate,
                                    String domain) implements TextData, IdData, UrlData {

    public String getSubject() {
        return subject == null ? "" : subject;
    }

    public String getTextBody() {
        return textBody == null ? "" : textBody;
    }

    public SalesforceEmailRecord updateDomain(final String domain) {
        return new SalesforceEmailRecord(id, subject, textBody, messageDate, domain);
    }

    @Override
    public String generateId() {
        return id;
    }

    @Override
    public String generateText() {
        return getSubject() + "\n" + getTextBody();
    }

    @Override
    public String generateLinkText() {
        return "Salesforce Email " + generateId();
    }

    @Override
    public String generateUrl() {
        return "https://" + (domain == null ? "fixme" : domain) + ".lightning.force.com/lightning/r/EmailMessage/" + generateId() + "/view";
    }
}

