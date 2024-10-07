package secondbrain.infrastructure.ollama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OllamaResponse(String model,
                             String created_at,
                             String done,
                             String done_reason,
                             List<String> context,
                             String total_duration,
                             String load_duration,
                             String prompt_eval_count,
                             String prompt_eval_duration,
                             String eval_count,
                             String eval_duration,
                             String response) {
}
