package secondbrain.infrastructure.azure.api;

import java.util.List;

public interface PromptTextGenerator {
    String generatePromptText();
    List<AzureRequestMessage> getMessages();
    PromptTextGenerator updateMessages(List<AzureRequestMessage> newMessages);
}
