package secondbrain.infrastructure.slack.json;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.slack.api.model.Conversation;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A Jackson module that registers a mixin for the Slack SDK's Conversation class
 * to handle lenient deserialization of Integer fields that may be stored as JSON objects
 * in cached data.
 */
@ApplicationScoped
public class SlackConversationModule extends SimpleModule {
    @Override
    public void setupModule(final SetupContext context) {
        super.setupModule(context);
        context.setMixInAnnotations(Conversation.class, ConversationMixIn.class);
    }
}
