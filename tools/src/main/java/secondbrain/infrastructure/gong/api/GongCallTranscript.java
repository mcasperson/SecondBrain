package secondbrain.infrastructure.gong.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GongCallTranscript(List<GongCallTranscriptCollection> callTranscripts) {
    public String getTranscript(final GongCallExtensive call) {
        if (callTranscripts == null) {
            return "";
        }

        return callTranscripts.stream()
                .filter(Objects::nonNull)
                .flatMap(transcript -> transcript.getTranscriptItems().stream())
                .filter(Objects::nonNull)
                .map(transcriptItem -> call.getPartyNameFromId(transcriptItem.speakerId()) +
                        ":\n" +
                        transcriptItem.getTranscript())
                .reduce("", (a, b) -> a + "\n" + b);
    }
}
