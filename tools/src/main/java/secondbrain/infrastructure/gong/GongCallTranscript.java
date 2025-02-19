package secondbrain.infrastructure.gong;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GongCallTranscript(List<GongCallTranscriptCollection> callTranscripts) {
    public String getTranscript() {
        if (callTranscripts == null) {
            return "";
        }

        return callTranscripts.stream()
                .flatMap(transcript -> transcript.transcript().stream())
                .flatMap(transcriptItem -> transcriptItem.sentences().stream())
                .map(GongCallTranscriptItemSentence::text)
                .reduce("", (a, b) -> a + "\n" + b);
    }
}
