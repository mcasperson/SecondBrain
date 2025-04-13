package secondbrain.domain.tools.slack.model;

public record SlackSearchResultResource(String id, String timestamp, String channelName, String text,
                                        String permalink) {
}
