package secondbrain.infrastructure.youtube.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import secondbrain.infrastructure.zendesk.api.ZenDeskTicket;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record YoutubePlaylists(List<YoutubePlaylistsItem> items, String nextPageToken) {
    public YoutubePlaylistsItem[] getItemsArray() {
        if (results == null) {
            return new ZenDeskTicket[]{};
        }

        return results.toArray(new ZenDeskTicket[0]);
    }
}
