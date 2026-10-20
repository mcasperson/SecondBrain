package secondbrain.infrastructure.slack.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.api.model.block.LayoutBlock;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class SlackJacksonLayoutBlockDeserializerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SlackJacksonLayoutBlockDeserializer deserializer = new SlackJacksonLayoutBlockDeserializer();

    @Test
    void handlesTheLayoutBlockType() {
        assertEquals(LayoutBlock.class, deserializer.handledType());
    }

    @Test
    void readsAnyBlockAsAPlaceholderAndConsumesIt() throws IOException {
        try (JsonParser parser = mapper.createParser("{\"type\": \"section\", \"block_id\": \"b1\", \"fields\": [{\"text\": \"x\"}]} 42")) {
            parser.nextToken();

            final LayoutBlock block = deserializer.deserialize(parser, mapper.getDeserializationContext());

            assertInstanceOf(PlaceholderLayoutBlock.class, block);
            assertEquals("", block.getType());
            // The whole block object was read, so the next value is the trailing number
            assertEquals(42, mapper.readValue(parser, Integer.class));
        }
    }

    @Test
    void readsAnEmptyObject() throws IOException {
        try (JsonParser parser = mapper.createParser("{}")) {
            parser.nextToken();

            assertInstanceOf(PlaceholderLayoutBlock.class, deserializer.deserialize(parser, mapper.getDeserializationContext()));
        }
    }
}
