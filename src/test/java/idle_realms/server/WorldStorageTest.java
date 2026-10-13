package idle_realms.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import idle_realms.core.Command;
import idle_realms.core.CommandHandler;
import idle_realms.core.CommandRequest;
import idle_realms.core.EconomySession.Readiness;
import idle_realms.core.EconomySnapshotText;
import idle_realms.core.EconomyState.RequestStatus;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lebenszyklus gegen den echten Welt-Speicher (SavedDataStorage) im Datenordner einer Welt. Läuft in der NeoForge-Unit-Test-Umgebung.
 * Der Neustart besteht aus einem neuen Speicher-Objekt auf demselben Ordner und einem neuen Lebenszyklus.
 */
class WorldStorageTest {
    private static final UUID REALM = new UUID(7, 7);

    @TempDir
    Path world;

    @Test
    void firstSaveThenRestartOnTheSameWorldFolderRestoresTheState() {
        Path data = world.resolve("data");
        EconomyLifecycle first = new EconomyLifecycle();
        first.attach(store(data), 0);
        assertEquals(Readiness.ABSENT, first.session().readiness());
        first.initialize(REALM, 0);
        first.tick(3_500);
        String saved = text(first);
        first.detach();

        EconomyLifecycle restarted = new EconomyLifecycle();
        restarted.attach(store(data), 3_500);

        assertEquals(Readiness.READY, restarted.session().readiness());
        assertEquals(saved, text(restarted));
    }

    @Test
    void pendingCommandSurvivesRestartAndRunsOnce() {
        Path data = world.resolve("data");
        EconomyLifecycle first = new EconomyLifecycle();
        first.attach(store(data), 0);
        first.initialize(REALM, 0);
        first.session().commands().orElseThrow().submit(request(1, new Command.Pause(1)), 0);
        first.detach();

        EconomyLifecycle restarted = new EconomyLifecycle();
        restarted.attach(store(data), 5_000);
        CommandHandler handler = restarted.session().commands().orElseThrow();
        assertEquals(RequestStatus.APPLIED, handler.status(new UUID(0, 1)).orElseThrow().status());
        restarted.detach();

        EconomyLifecycle again = new EconomyLifecycle();
        again.attach(store(data), 6_000);
        assertEquals(RequestStatus.APPLIED, again.session().commands().orElseThrow().submit(request(1, new Command.Pause(1)), 6_000).status());
    }

    @Test
    void unreadableFileFailsClosedAndIsNotOverwritten() throws IOException {
        Path data = world.resolve("data");
        Path file = EconomyData.fileIn(data);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {1, 2, 3, 4, 5});
        byte[] before = Files.readAllBytes(file);

        EconomyLifecycle lifecycle = new EconomyLifecycle();
        lifecycle.attach(store(data), 0);

        assertEquals(Readiness.FAILED, lifecycle.session().readiness());
        assertThrows(IllegalStateException.class, () -> lifecycle.initialize(REALM, 0));
        lifecycle.detach();
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    void interruptedWriteLeavesThePreviousCompleteFile() throws Exception {
        Path data = world.resolve("data");
        Path file = EconomyData.fileIn(data);
        EconomyLifecycle first = new EconomyLifecycle();
        first.attach(store(data), 0);
        first.initialize(REALM, 0);
        first.tick(3_500);
        String previous = text(first);
        first.detach();

        byte[] before = Files.readAllBytes(file);
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        // Windows verweigert das Ersetzen einer geöffneten Datei; POSIX verweigert das Anlegen der Temp-Datei im schreibgeschützten Ordner.
        Path folder = file.getParent();
        EconomyLifecycle second = new EconomyLifecycle();
        second.attach(store(data), 3_500);
        try (FileChannel lock = windows ? FileChannel.open(file, StandardOpenOption.READ) : null) {
            if (!windows) Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"));
            second.tick(9_000);
            second.detach();
        } finally {
            if (!windows) Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));
        }

        assertArrayEquals(before, Files.readAllBytes(file));
        EconomyLifecycle recovered = new EconomyLifecycle();
        recovered.attach(store(data), 3_500);
        assertEquals(previous, text(recovered));
    }

    private static EconomyStore store(Path dataFolder) {
        SavedDataStorage storage = new SavedDataStorage(dataFolder, DataFixers.getDataFixer(), RegistryAccess.EMPTY);
        return new SavedDataEconomyStore(storage, dataFolder);
    }

    private static String text(EconomyLifecycle lifecycle) {
        return EconomySnapshotText.encode(lifecycle.session().snapshot().orElseThrow());
    }

    private static CommandRequest request(long seq, Command command) {
        return new CommandRequest(new UUID(0, seq), REALM, CommandHandler.DEFINITION_REVISION, 0, command);
    }
}
