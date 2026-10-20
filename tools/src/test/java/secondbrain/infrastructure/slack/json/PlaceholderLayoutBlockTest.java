package secondbrain.infrastructure.slack.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.api.model.block.LayoutBlock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlaceholderLayoutBlockTest {
    @Test
    void typeAndBlockIdAreEmpty() {
        final LayoutBlock block = new PlaceholderLayoutBlock();

        assertEquals("", block.getType());
        assertEquals("", block.getBlockId());
    }

    @Test
    void ignoresAllPropertiesWhenDeserialized() throws Exception {
        final PlaceholderLayoutBlock block = new ObjectMapper().readValue(
                "{\"type\": \"section\", \"block_id\": \"b1\", \"blockId\": \"b2\", \"text\": {\"type\": \"plain_text\"}}",
                PlaceholderLayoutBlock.class);

        // The values from the JSON are discarded
        assertEquals("", block.getType());
        assertEquals("", block.getBlockId());
    }
}
