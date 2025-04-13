package secondbrain.infrastructure.slack.api;

public record SlackSearchResultResource(String id, String timestamp, String channelName, String text,
                                        String permalink) {
}
