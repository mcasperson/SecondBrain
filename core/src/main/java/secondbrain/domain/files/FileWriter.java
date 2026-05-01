package secondbrain.domain.files;

import java.nio.file.Path;

public interface FileWriter {
    void write(String path, String content);

    void write(Path path, String content);

    void append(String path, String content);

    void append(Path path, String content);
}
