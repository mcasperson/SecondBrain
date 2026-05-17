package secondbrain.infrastructure.gong.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GongCallTranscriptCollection(List<GongCallTranscriptItem> transcript) {
	public List<GongCallTranscriptItem> getTranscriptItems() {
		return Objects.requireNonNullElse(transcript, List.of());
	}
}
