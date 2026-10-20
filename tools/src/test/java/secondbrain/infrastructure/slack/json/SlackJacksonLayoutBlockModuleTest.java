package secondbrain.infrastructure.slack.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.slack.api.model.block.LayoutBlock;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SlackJacksonLayoutBlockModule}, which allows the LayoutBlock interface to be deserialized.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(SlackJacksonLayoutBlockModule.class)
class SlackJacksonLayoutBlockModuleTest {
    private static final String BLOCKS = """
            [
            	{"type": "section", "block_id": "b1", "text": {"type": "mrkdwn", "text": "hello"}},
            	{"type": "divider"}
            ]
            """;

    @Inject
    private SlackJacksonLayoutBlockModule module;

    @Test
    void layoutBlocksAreReadAsPlaceholders() throws Exception {
        final List<LayoutBlock> blocks = new ObjectMapper()
                .registerModule(module)
                .readValue(BLOCKS, new TypeReference<List<LayoutBlock>>() {
                });

        assertEquals(2, blocks.size());
        blocks.forEach(block -> {
            assertInstanceOf(PlaceholderLayoutBlock.class, block);
            // The placeholder does not keep any of the block values
            assertEquals("", block.getType());
            assertEquals("", block.getBlockId());
        });
    }

    @Test
    void nullBlocksStayNull() throws Exception {
        final List<LayoutBlock> blocks = new ObjectMapper()
                .registerModule(module)
                .readValue("[null]", new TypeReference<List<LayoutBlock>>() {
                });

        assertEquals(1, blocks.size());
        assertNull(blocks.getFirst());
    }

    @Test
    void withoutTheModuleTheInterfaceCanNotBeDeserialized() {
        assertThrows(InvalidDefinitionException.class, () -> new ObjectMapper().readValue(BLOCKS, new TypeReference<List<LayoutBlock>>() {
        }));
    }
}
