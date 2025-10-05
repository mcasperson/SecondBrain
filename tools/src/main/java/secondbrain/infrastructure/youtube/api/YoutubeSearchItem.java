package secondbrain.infrastructure.youtube.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import secondbrain.infrastructure.youtube.model.YoutubeVideo;

@JsonIgnoreProperties(ignoreUnknown = true)
public record YoutubeSearchItem(YoutubeSearchItemId id, YoutubeSearchItemSnippet snippet) {
    public YoutubeVideo toYoutubeVideo() {
        return new YoutubeVideo(id.videoId(), snippet().title());
    }
}
