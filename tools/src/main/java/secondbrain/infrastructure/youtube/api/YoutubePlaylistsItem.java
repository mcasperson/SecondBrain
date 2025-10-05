package secondbrain.infrastructure.youtube.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import secondbrain.infrastructure.youtube.model.YoutubeVideo;

@JsonIgnoreProperties(ignoreUnknown = true)
public record YoutubePlaylistsItem(String id, YoutubePlaylistsItemSnippet snippet) {
    public YoutubeVideo toYoutubeVideo() {
        return new YoutubeVideo(snippet().resourceId().videoId(), snippet().title());
    }
}
