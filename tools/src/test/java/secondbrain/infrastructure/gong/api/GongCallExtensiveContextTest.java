package secondbrain.infrastructure.gong.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

// Gong returns null for missing fields, so contexts are built with null values
@SuppressWarnings("NullAway")
class GongCallExtensiveContextTest {
    private static final GongCallExtensiveContextObjectField ACCOUNT_NAME = new GongCallExtensiveContextObjectField("Name", "Acme");
    private static final GongCallExtensiveContextObjectField ACCOUNT_ID = new GongCallExtensiveContextObjectField("Id", "001");
    private static final GongCallExtensiveContextObjectField OPPORTUNITY_NAME = new GongCallExtensiveContextObjectField("Name", "Renewal");
    private static final GongCallExtensiveContextObjectField OPPORTUNITY_STAGE = new GongCallExtensiveContextObjectField("Stage", "Closed");

    private static final GongCallExtensiveContextObject ACCOUNT = new GongCallExtensiveContextObject("Account", "001", List.of(ACCOUNT_NAME, ACCOUNT_ID));
    private static final GongCallExtensiveContextObject OPPORTUNITY = new GongCallExtensiveContextObject("Opportunity", "006", List.of(OPPORTUNITY_NAME, OPPORTUNITY_STAGE));

    private static final GongCallExtensiveContext CONTEXT = new GongCallExtensiveContext("Salesforce", List.of(ACCOUNT, OPPORTUNITY));

    @Test
    void getObjectsReturnsTheObjects() {
        assertEquals(List.of(ACCOUNT, OPPORTUNITY), CONTEXT.getObjects());
    }

    @Test
    void getObjectsReturnsAnEmptyListForNullObjects() {
        assertEquals(List.of(), new GongCallExtensiveContext("Salesforce", null).getObjects());
    }

    @Test
    void getObjectByFieldNameReturnsTheFirstMatchAcrossObjects() {
        assertEquals(Optional.of(ACCOUNT_NAME), CONTEXT.getObject("Name"));
        assertEquals(Optional.of(OPPORTUNITY_STAGE), CONTEXT.getObject("Stage"));
    }

    @Test
    void getObjectByFieldNameIsCaseSensitive() {
        assertEquals(Optional.empty(), CONTEXT.getObject("name"));
    }

    @Test
    void getObjectByFieldNameReturnsEmptyWhenNothingMatches() {
        assertEquals(Optional.empty(), CONTEXT.getObject("Missing"));
        assertEquals(Optional.empty(), new GongCallExtensiveContext("Salesforce", null).getObject("Name"));
    }

    @Test
    void getObjectByFieldNameSkipsObjectsWithNullFieldsAndFieldsWithNullNames() {
        final GongCallExtensiveContext context = new GongCallExtensiveContext("Salesforce", List.of(
                new GongCallExtensiveContextObject("Account", "001", null),
                new GongCallExtensiveContextObject("Contact", "003", List.of(new GongCallExtensiveContextObjectField(null, "x"), ACCOUNT_ID))));

        assertEquals(Optional.of(ACCOUNT_ID), context.getObject("Id"));
    }

    @Test
    void getObjectByTypeAndFieldNameOnlySearchesObjectsOfThatType() {
        assertEquals(Optional.of(OPPORTUNITY_NAME), CONTEXT.getObject("Opportunity", "Name"));
        assertEquals(Optional.of(ACCOUNT_NAME), CONTEXT.getObject("Account", "Name"));
    }

    @Test
    void getObjectByTypeAndFieldNameReturnsEmptyWhenTheFieldIsOnAnotherType() {
        assertEquals(Optional.empty(), CONTEXT.getObject("Account", "Stage"));
    }

    @Test
    void getObjectByTypeAndFieldNameReturnsEmptyForAnUnknownType() {
        assertEquals(Optional.empty(), CONTEXT.getObject("Lead", "Name"));
        assertEquals(Optional.empty(), new GongCallExtensiveContext("Salesforce", null).getObject("Account", "Name"));
    }

    @Test
    void getObjectByTypeAndFieldNameSkipsObjectsWithoutAType() {
        final GongCallExtensiveContext context = new GongCallExtensiveContext("Salesforce", List.of(
                new GongCallExtensiveContextObject(null, "000", List.of(OPPORTUNITY_NAME)),
                ACCOUNT));

        assertEquals(Optional.of(ACCOUNT_NAME), context.getObject("Account", "Name"));
    }
}
