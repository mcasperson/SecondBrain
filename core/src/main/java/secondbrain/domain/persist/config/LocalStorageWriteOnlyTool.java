package secondbrain.domain.persist.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.tika.utils.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Arrays;
import java.util.Optional;

@ApplicationScoped
public class LocalStorageWriteOnlyTool {
    @Inject
    @ConfigProperty(name = "sb.cache.writeonlytool")
    private Optional<String> writeOnlyTool;

    public boolean isToolWriteOnly(final String toolName) {
        if (StringUtils.isBlank(toolName)) {
            return false;
        }

        return writeOnlyTool.isPresent() && Arrays.asList(writeOnlyTool.get().split(",")).contains(toolName);
    }
}

