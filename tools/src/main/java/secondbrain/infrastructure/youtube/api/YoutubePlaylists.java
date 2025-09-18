package secondbrain.infrastructure.youtube.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record YoutubePlaylists(List<YoutubePlaylistsItem> items, String nextPageToken) {
    public YoutubePlaylistsItem[] getItemsArray() {
        if (items == null) {
            return new YoutubePlaylistsItem[]{};
        }

        return items.toArray(new YoutubePlaylistsItem[0]);
    }
}
