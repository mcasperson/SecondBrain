package secondbrain.infrastructure.salesforce.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SalesforceAccountQueryTest {
    private static final String RESPONSE = """
            {
                "totalSize": 1,
                "done": true,
                "records": [
                    {
                        "attributes": {
                            "type": "Account",
                            "url": "/services/data/v64.0/sobjects/Account/001Qq00000a53mwIAA"
                        },
                        "Id": "001Qq00000a53mwIAA",
                        "Name": "Foodstuffs North Island",
                        "Owner": {
                            "attributes": {
                                "type": "User",
                                "url": "/services/data/v64.0/sobjects/User/005Qq000003n9I9IAI"
                            },
                            "Name": "Sophia Gazi",
                            "Email": "sophia.gazi@octopus.com"
                        },
                        "BillingStreet": "35 Landing Dr",
                        "BillingCity": "Mangere",
                        "BillingState": null,
                        "BillingPostalCode": null,
                        "BillingCountry": "NZ",
                        "Industry": "Grocery Retail",
                        "NumberOfEmployees": 24000
                    }
                ]
            }
            """;

    @Test
    void parsesTheAccountQueryResponse() throws Exception {
        final SalesforceAccountQuery query = new ObjectMapper().readValue(RESPONSE, SalesforceAccountQuery.class);

        assertEquals(new SalesforceAccountQuery(1, true, List.of(new SalesforceAccountRecord(
                "001Qq00000a53mwIAA",
                "Foodstuffs North Island",
                new SalesforceAccountOwner("Sophia Gazi", "sophia.gazi@octopus.com"),
                "35 Landing Dr",
                "Mangere",
                null,
                null,
                "NZ",
                "Grocery Retail",
                24000))), query);
    }

    @Test
    void defaultsMissingValues() {
        final SalesforceAccountRecord record = new SalesforceAccountRecord(null, null, null, null, null, null, null, null, null, null);

        assertEquals("", record.getId());
        assertEquals("", record.getName());
        assertEquals("", record.getOwner().getName());
        assertEquals("", record.getOwner().getEmail());
        assertEquals("", record.getBillingStreet());
        assertEquals("", record.getBillingCity());
        assertEquals("", record.getBillingState());
        assertEquals("", record.getBillingPostalCode());
        assertEquals("", record.getBillingCountry());
        assertEquals("", record.getIndustry());
        assertEquals("", record.getNumberOfEmployees());
        assertEquals(List.of(), new SalesforceAccountQuery(null, null, null).getRecords());
    }
}
