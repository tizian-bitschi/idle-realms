package idle_realms.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoreBoundaryTest {
    private static final Path CORE = Path.of("src/main/java/idle_realms/core");

    @Test
    void coreImportsNoMinecraftTypes() throws IOException {
        List<Path> sources;
        try (var files = Files.walk(CORE)) {
            sources = files.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertTrue(!sources.isEmpty(), "Kernquellen nicht gefunden: " + CORE);
        for (Path source : sources) {
            String text = Files.readString(source);
            assertFalse(text.contains("import net.minecraft.") || text.contains("import net.neoforged."),
                    source + " importiert Minecraft-Typen");
        }
    }
}
