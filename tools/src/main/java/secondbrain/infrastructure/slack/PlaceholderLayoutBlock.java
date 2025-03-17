package secondbrain.infrastructure.slack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.slack.api.model.block.LayoutBlock;

/**
 * A placeholder layout block for deserialization of the LayoutBlock interface. We don't use any of these
 * values, so this is just a dummy class.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlaceholderLayoutBlock implements LayoutBlock {
    @Override
    public String getType() {
        return "";
    }

    @Override
    public String getBlockId() {
        return "";
    }
}
