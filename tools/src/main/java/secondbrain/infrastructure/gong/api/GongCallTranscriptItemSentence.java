package secondbrain.infrastructure.gong.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GongCallTranscriptItemSentence(String text) {
}
