package secondbrain.infrastructure.gong.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GongCallTranscriptItem(String speakerId, List<GongCallTranscriptItemSentence> sentences) {
    public String getTranscript() {
        return sentences().
                stream()
                .map(GongCallTranscriptItemSentence::text)
                .reduce("", (a, b) -> a + "\n" + b);
    }
}
