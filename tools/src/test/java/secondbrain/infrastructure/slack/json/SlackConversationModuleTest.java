package secondbrain.infrastructure.slack.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.slack.api.model.Conversation;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SlackConversationModule}, which adds lenient Integer handling to the Slack SDK Conversation class.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(SlackConversationModule.class)
class SlackConversationModuleTest {
    @Inject
    private SlackConversationModule module;

    private ObjectMapper mapper() {
        return new ObjectMapper().registerModule(module);
    }

    @Test
    void objectValuesForIntegerFieldsAreReadAsNull() throws Exception {
        final Conversation conversation = mapper().readValue("""
                {"id": "C1", "name": "general", "created": {"$numberLong": "1700000000"}, "numOfMembers": {"$numberInt": "5"}}
                """, Conversation.class);

        assertEquals("C1", conversation.getId());
        assertEquals("general", conversation.getName());
        assertNull(conversation.getCreated());
        assertNull(conversation.getNumOfMembers());
    }

    @Test
    void plainAndStringNumbersAreStillRead() throws Exception {
        final Conversation conversation = mapper().readValue("""
                {"created": 1700000000, "unlinked": "3", "numOfMembers": 12, "unreadCount": [1], "unreadCountDisplay": "x", "dateConnected": null, "isMoved": 1}
                """, Conversation.class);

        assertEquals(1700000000, conversation.getCreated());
        assertEquals(3, conversation.getUnlinked());
        assertEquals(12, conversation.getNumOfMembers());
        assertNull(conversation.getUnreadCount());
        assertNull(conversation.getUnreadCountDisplay());
        assertNull(conversation.getDateConnected());
        assertEquals(1, conversation.getIsMoved());
    }

    @Test
    void unknownPropertiesAreIgnored() throws Exception {
        final Conversation conversation = mapper().readValue("{\"id\": \"C2\", \"notASlackField\": true}", Conversation.class);

        assertEquals("C2", conversation.getId());
    }

    @Test
    void withoutTheModuleObjectValuesAndUnknownPropertiesFail() {
        final ObjectMapper plain = new ObjectMapper();

        assertThrows(MismatchedInputException.class,
                () -> plain.readValue("{\"created\": {\"$numberLong\": \"1\"}}", Conversation.class));
        assertThrows(UnrecognizedPropertyException.class,
                () -> plain.readValue("{\"notASlackField\": true}", Conversation.class));
    }
}
