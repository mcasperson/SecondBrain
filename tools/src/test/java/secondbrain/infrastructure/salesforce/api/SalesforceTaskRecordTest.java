package secondbrain.infrastructure.salesforce.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

// Salesforce returns null for missing fields, so tasks are built with null values
@SuppressWarnings("NullAway")
class SalesforceTaskRecordTest {
    private static final SalesforceTaskRecord TASK = new SalesforceTaskRecord(
            "00TQq000001", "Discussed the renewal", "Call", "Renewal call", "2025-01-02T03:04:05.000+0000", "octopus");

    @Test
    void gettersReturnTheFieldValues() {
        assertEquals("Discussed the renewal", TASK.getDescription());
        assertEquals("Call", TASK.getType());
        assertEquals("Renewal call", TASK.getSubject());
        assertEquals("2025-01-02T03:04:05.000+0000", TASK.getCreatedDate());
    }

    @Test
    void gettersReturnEmptyStringsForNullFields() {
        final SalesforceTaskRecord task = new SalesforceTaskRecord("id", null, null, null, null, null);

        assertEquals("", task.getDescription());
        assertEquals("", task.getType());
        assertEquals("", task.getSubject());
        assertEquals("", task.getCreatedDate());
    }

    @Test
    void generateIdReturnsTheId() {
        assertEquals("00TQq000001", TASK.generateId());
    }

    @Test
    void generateTextCombinesSubjectDateAndDescription() {
        assertEquals("Renewal call 2025-01-02T03:04:05.000+0000\nDiscussed the renewal", TASK.generateText());
    }

    @Test
    void generateTextUsesEmptyStringsForNullFields() {
        assertEquals(" \n", new SalesforceTaskRecord("id", null, null, null, null, null).generateText());
    }

    @Test
    void generateLinkTextIncludesTheId() {
        assertEquals("Salesforce Task 00TQq000001", TASK.generateLinkText());
    }

    @Test
    void generateUrlUsesTheDomain() {
        assertEquals("https://octopus.lightning.force.com/lightning/r/Task/00TQq000001/view", TASK.generateUrl());
    }

    @Test
    void generateUrlUsesAPlaceholderWithoutADomain() {
        final SalesforceTaskRecord task = new SalesforceTaskRecord("00TQq000001", null, null, null, null, null);

        assertEquals("https://fixme.lightning.force.com/lightning/r/Task/00TQq000001/view", task.generateUrl());
    }

    @Test
    void updateDomainReturnsACopyWithTheNewDomain() {
        final SalesforceTaskRecord updated = TASK.updateDomain("other");

        assertEquals(new SalesforceTaskRecord("00TQq000001", "Discussed the renewal", "Call", "Renewal call",
                "2025-01-02T03:04:05.000+0000", "other"), updated);
        assertEquals("octopus", TASK.domain());
        assertEquals("https://other.lightning.force.com/lightning/r/Task/00TQq000001/view", updated.generateUrl());
    }

    @Test
    void deserializesSalesforceFieldNamesAndIgnoresUnknownFields() throws JsonProcessingException {
        final String json = """
                {"attributes": {"type": "Task"}, "Id": "00T1", "Description": "Notes", "Type": "Email",
                "Subject": "Follow up", "CreatedDate": "2025-02-03", "Unknown": 1}
                """;

        final SalesforceTaskRecord task = new ObjectMapper().readValue(json, SalesforceTaskRecord.class);

        assertEquals(new SalesforceTaskRecord("00T1", "Notes", "Email", "Follow up", "2025-02-03", null), task);
    }
}
