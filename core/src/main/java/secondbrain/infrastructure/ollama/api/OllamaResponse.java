package secondbrain.infrastructure.ollama.api;

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
    public OllamaResponse replaceResponse(final String response) {
        return new OllamaResponse(model, created_at, done, done_reason, context, total_duration, load_duration, prompt_eval_count, prompt_eval_duration, eval_count, eval_duration, response);
    }
}
