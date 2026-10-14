package idle_realms.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Architekturregel aus ADR 0001: Der Kern importiert keine Typen aus net.minecraft oder net.neoforged. Die Prüfung liest die
 * Quelltexte direkt, weil ArchUnit in der NeoForge-Testumgebung nicht gestartet werden kann.
 */
class CoreBoundaryTest {
    @Test
    void coreDoesNotImportMinecraftOrNeoForge() throws IOException {
        Path core = Path.of(System.getProperty("idle_realms.sourceRoot"), "idle_realms", "core");
        List<String> offenders;
        try (Stream<Path> files = Files.walk(core)) {
            offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(CoreBoundaryTest::importsPlatform)
                    .map(Path::toString)
                    .toList();
        }
        assertTrue(offenders.isEmpty(), "Kern importiert Plattformtypen: " + offenders);
    }

    private static boolean importsPlatform(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .anyMatch(line -> line.startsWith("import net.minecraft.") || line.startsWith("import net.neoforged."));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
