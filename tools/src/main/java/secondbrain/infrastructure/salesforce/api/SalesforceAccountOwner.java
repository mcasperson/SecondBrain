package secondbrain.infrastructure.salesforce.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SalesforceAccountOwner(@JsonProperty("Name") @Nullable String name,
                                     @JsonProperty("Email") @Nullable String email) {

    public String getName() {
        return name == null ? "" : name;
    }

    public String getEmail() {
        return email == null ? "" : email;
    }
}
