package secondbrain.infrastructure.slack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.slack.api.model.block.LayoutBlock;

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
