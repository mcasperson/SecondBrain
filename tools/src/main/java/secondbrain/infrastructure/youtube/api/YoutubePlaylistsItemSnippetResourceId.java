package secondbrain.infrastructure.youtube.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record YoutubePlaylistsItemSnippetResourceId(String kind, String videoId) {
}
