package secondbrain.infrastructure.gong.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GongCallTranscriptItem(List<GongCallTranscriptItemSentence> sentences) {
}
