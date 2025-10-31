package secondbrain.domain.persist.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.tika.utils.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Arrays;
import java.util.Optional;

@ApplicationScoped
public class LocalStorageDisableTool {
    @Inject
    @ConfigProperty(name = "sb.cache.disabletool")
    private Optional<String> disableTool;

    public boolean isToolDisabled(final String toolName) {
        if (StringUtils.isBlank(toolName)) {
            return false;
        }

        return disableTool.isPresent() && Arrays.asList(disableTool.get().split(",")).contains(toolName);
    }
}
