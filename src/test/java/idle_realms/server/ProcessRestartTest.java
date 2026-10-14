package idle_realms.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import idle_realms.core.Command;
import idle_realms.core.CommandHandler;
import idle_realms.core.CommandRequest;
import idle_realms.core.EconomySession.Readiness;
import idle_realms.core.EconomySnapshotText;
import idle_realms.core.EconomyState.RequestStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Prozessneustart (Issue #7): Die Phasen laufen in zwei getrennten JVMs über {@link ProcessRestartDriver} auf demselben
 * Weltordner. Ohne Phasen-Eigenschaft ist die Klasse deaktiviert und läuft nicht im normalen Testlauf.
 */
@EnabledIfSystemProperty(named = "idle_realms.phase", matches = "write|read")
class ProcessRestartTest {
    private static final UUID REALM = new UUID(9, 9);
    private static final String EXPECTED = "expected.txt";

    @Test
    void writePhase() throws Exception {
        Path world = Path.of(System.getProperty("idle_realms.world"));
        Files.createDirectories(world);
        Path data = world.resolve("data");
        EconomyLifecycle lifecycle = new EconomyLifecycle();
        lifecycle.attach(store(data), 0);
        lifecycle.initialize(REALM, 0);
        lifecycle.tick(3_500);
        // Der Befehl wirkt erst am nächsten Schrittbeginn und ist beim Beenden des Prozesses noch ausstehend.
        lifecycle.session().commands().orElseThrow().submit(request(1, new Command.Pause(1)), 3_500);
        Files.writeString(world.resolve(EXPECTED), text(lifecycle));
        lifecycle.detach();
        assertTrue(Files.exists(EconomyData.fileIn(data)));
    }

    @Test
    void readPhase() throws Exception {
        Path world = Path.of(System.getProperty("idle_realms.world"));
        Path data = world.resolve("data");
        String expected = Files.readString(world.resolve(EXPECTED));

        EconomyLifecycle lifecycle = new EconomyLifecycle();
        lifecycle.attach(store(data), 3_500);
        assertEquals(Readiness.READY, lifecycle.session().readiness());
        assertEquals(expected, text(lifecycle));

        CommandHandler handler = lifecycle.session().commands().orElseThrow();
        lifecycle.tick(5_000);
        assertEquals(RequestStatus.APPLIED, handler.status(new UUID(0, 1)).orElseThrow().status());
        assertTrue(lifecycle.session().snapshot().orElseThrow().buildings().get(0).paused());
        // Eine Wiederholung derselben Kennung liefert den gespeicherten Stand und führt den Befehl nicht erneut aus.
        assertEquals(RequestStatus.APPLIED, handler.submit(request(1, new Command.Pause(1)), 5_000).status());
        assertTrue(lifecycle.session().snapshot().orElseThrow().buildings().get(0).paused());
        lifecycle.detach();
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
