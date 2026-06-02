package secondbrain.domain.persist.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.tika.utils.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Arrays;
import java.util.Optional;

@ApplicationScoped
public class LocalStorageReadOnlyTool {
    @Inject
    @ConfigProperty(name = "sb.cache.readonlytool")
    private Optional<String> readOnlyTool;

    public boolean isToolReadOnly(final String toolName) {
        if (StringUtils.isBlank(toolName)) {
            return false;
        }

        return readOnlyTool.isPresent() && Arrays.asList(readOnlyTool.get().split(",")).contains(toolName);
    }
}

