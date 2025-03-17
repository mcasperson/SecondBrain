package secondbrain.infrastructure.slack;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.slack.api.model.block.LayoutBlock;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class SlackJacksonLayoutBlockModule extends SimpleModule {
    {
        addDeserializer(LayoutBlock.class, new SlackJacksonLayoutBlockDeserializer());
    }
}