package secondbrain.infrastructure.azure.api;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * We need a way to keep on top of all the property renames, new fields, and other quriks that come with supporting
 * different API versions. Our needs are usually pretty simple, setting things like token counts, messages, and reasoning.
 * We just need to be able to match the required fields across different API versions.
 */
public class AzureRequestMaxCompletionTokensFactory {
    private static final String V_2025_04_01_PREVIEW =  "2025-04-01-preview";

    public static PromptTextGenerator generateRequest(final List<AzureRequestMessage> messages,
                                  @Nullable final Integer maxOutputTokens,
                                  @Nullable final String reasoningEffort,
                                  final String model,
                                  final String apiVersion) {
        if (V_2025_04_01_PREVIEW.equals(apiVersion)) {
            return new AzureRequestMaxCompletionTokens_2025_04_01_preview(messages, maxOutputTokens, reasoningEffort, model);
        }

        return new AzureRequestMaxCompletionTokens_2024_05_01_preview(messages, maxOutputTokens, model);
    }

    public static PromptTextGenerator generateRequest(final List<AzureRequestMessage> messages,
                                         final Integer maxOutputTokens,
                                         final String model,
                                         final String apiVersion) {
        if (V_2025_04_01_PREVIEW.equals(apiVersion)) {
            return new AzureRequestMaxCompletionTokens_2025_04_01_preview(messages, maxOutputTokens, "", model);
        }

        return new AzureRequestMaxCompletionTokens_2024_05_01_preview(messages, maxOutputTokens, model);
    }

    public static PromptTextGenerator generateRequest(final List<AzureRequestMessage> messages,
                                         final String model,
                                         final String apiVersion) {
        if (V_2025_04_01_PREVIEW.equals(apiVersion)) {
            return new AzureRequestMaxCompletionTokens_2025_04_01_preview(messages, model);
        }

        return new AzureRequestMaxCompletionTokens_2024_05_01_preview(messages, model);
    }
}
