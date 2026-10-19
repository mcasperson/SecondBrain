package secondbrain.infrastructure.salesforce.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SalesforceAccountRecord(@JsonProperty("Id") @Nullable String id,
                                      @JsonProperty("Name") @Nullable String name,
                                      @JsonProperty("Owner") @Nullable SalesforceAccountOwner owner,
                                      @JsonProperty("BillingStreet") @Nullable String billingStreet,
                                      @JsonProperty("BillingCity") @Nullable String billingCity,
                                      @JsonProperty("BillingState") @Nullable String billingState,
                                      @JsonProperty("BillingPostalCode") @Nullable String billingPostalCode,
                                      @JsonProperty("BillingCountry") @Nullable String billingCountry,
                                      @JsonProperty("Industry") @Nullable String industry,
                                      @JsonProperty("NumberOfEmployees") @Nullable Integer numberOfEmployees) {

    public String getId() {
        return id == null ? "" : id;
    }

    public String getName() {
        return name == null ? "" : name;
    }

    public SalesforceAccountOwner getOwner() {
        return owner == null ? new SalesforceAccountOwner("", "") : owner;
    }

    public String getBillingStreet() {
        return billingStreet == null ? "" : billingStreet;
    }

    public String getBillingCity() {
        return billingCity == null ? "" : billingCity;
    }

    public String getBillingState() {
        return billingState == null ? "" : billingState;
    }

    public String getBillingPostalCode() {
        return billingPostalCode == null ? "" : billingPostalCode;
    }

    public String getBillingCountry() {
        return billingCountry == null ? "" : billingCountry;
    }

    public String getIndustry() {
        return industry == null ? "" : industry;
    }

    /**
     * Returns the number of employees as a string, or an empty string if Salesforce has no value.
     */
    public String getNumberOfEmployees() {
        return numberOfEmployees == null ? "" : numberOfEmployees.toString();
    }
}
