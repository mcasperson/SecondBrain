package secondbrain.infrastructure.gong.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GongCallTranscriptItem(String speakerId, List<GongCallTranscriptItemSentence> sentences) {
    public List<GongCallTranscriptItemSentence> getSentences() {
        return Objects.requireNonNullElse(sentences, List.of());
    }

    public String getTranscript() {
        return getSentences().
                stream()
                .map(GongCallTranscriptItemSentence::text)
                .reduce("", (a, b) -> a + "\n" + b);
    }
}
