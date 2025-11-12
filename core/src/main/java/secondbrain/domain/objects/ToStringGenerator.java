package secondbrain.domain.objects;

import java.util.List;

public interface ToStringGenerator {
    String generateGetterConfig(Object obj);

    String generateGetterConfig(Object obj, List<String> excludeFields);
}
