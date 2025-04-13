package secondbrain.infrastructure.slack.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.slack.api.model.block.LayoutBlock;

import java.io.IOException;

public class SlackJacksonLayoutBlockDeserializer extends StdDeserializer<LayoutBlock> {
    public SlackJacksonLayoutBlockDeserializer() {
        super(LayoutBlock.class);
    }

    public LayoutBlock deserialize(final JsonParser jsonParser, final DeserializationContext context) throws IOException {
        return jsonParser.readValueAs(PlaceholderLayoutBlock.class);
    }
}
