package secondbrain.domain.hanlder;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
public class ToolSelectionTest {

    @Container
    public GenericContainer<?> ollamaContainer = new GenericContainer<>("ollama/ollama:latest")
            .withFileSystemBind(Paths.get(System.getProperty("java.io.tmpdir")).resolve(Paths.get("secondbrain")).toString(), "/root/.ollama")
            .withExposedPorts(11434);

    @Test
    @Disabled
    void testOllamaContainerIsRunning() throws IOException, InterruptedException {
        ollamaContainer.start();
        ollamaContainer.execInContainer("/usr/bin/ollama", "pull", "llama3.2");
        ollamaContainer.execInContainer("/usr/bin/ollama", "pull", "llama3.1");
        assertTrue(ollamaContainer.isRunning());
    }
}