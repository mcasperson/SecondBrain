package secondbrain.infrastructure.youtube.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record YoutubeSearch(String nextPageToken, List<YoutubeSearchItem> items) {
    public YoutubeSearchItem[] getItemsArray() {
        if (items == null) {
            return new YoutubeSearchItem[]{};
        }

        return items.toArray(new YoutubeSearchItem[0]);
    }
}
