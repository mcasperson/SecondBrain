package secondbrain.infrastructure.dovetail.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DovetailDataExportResponse(
        DovetailDataExport data
) {
}

