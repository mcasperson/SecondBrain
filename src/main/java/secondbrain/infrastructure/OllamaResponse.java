package secondbrain.infrastructure;

import java.util.List;

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
