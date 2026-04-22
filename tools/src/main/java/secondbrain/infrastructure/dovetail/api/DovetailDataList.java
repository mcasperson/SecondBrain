package secondbrain.infrastructure.dovetail.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DovetailDataList(
        List<DovetailDataItem> data,
        DovetailPage page
) {
    public DovetailDataItem[] getDataArray() {
        return data == null ? new DovetailDataItem[0] : data.toArray(new DovetailDataItem[0]);
    }
}

