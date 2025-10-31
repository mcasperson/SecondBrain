package secondbrain.domain.files;

import java.nio.file.Path;

public interface PathBuilder {
    Path getFilePath(String directory, String path);
}
