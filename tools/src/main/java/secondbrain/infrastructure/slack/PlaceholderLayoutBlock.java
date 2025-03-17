package secondbrain.infrastructure.slack;

import com.slack.api.model.block.LayoutBlock;

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
