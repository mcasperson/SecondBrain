package secondbrain.infrastructure.youtube;

import secondbrain.infrastructure.youtube.api.YoutubePlaylistsItem;

import java.util.List;

public interface YoutubeClient {
    List<YoutubePlaylistsItem> getPlaylistItems(String playlistId, String pageToken, String key);

    String getTranscript(String videoId, String lang);
}
