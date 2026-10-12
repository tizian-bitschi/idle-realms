package idle_realms.server;

import idle_realms.IdleRealms;
import idle_realms.core.EconomyException;
import idle_realms.core.EconomySession;
import idle_realms.core.EconomySession.Readiness;
import idle_realms.core.EconomySnapshotText;
import idle_realms.core.PlotZone;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Bindet die Wirtschaft an den Server-Lebenszyklus (Issue #7): laden beim Start, anlegen bei der Erstinitialisierung,
 * fortschreiben pro Tick, speichern und entladen beim Stoppen. Chunks spielen keine Rolle; Betriebe bleiben im Register.
 */
public final class EconomyLifecycle {
    // shortcut: Bauzonen sind noch nicht gegen die Welt geprüft; Bau und Versetzen werden deshalb abgewiesen, bis die Startregion (#9) die Zonen liefert
    private static final PlotZone ZONE_BLOCKED = plot -> false;
    /** Obergrenze für den Verlust bei einem Absturz zwischen zwei Speicherungen: 30 Sekunden. */
    private static final int SAVE_INTERVAL_TICKS = 30 * 20;

    private final EconomySession session = new EconomySession();
    private EconomyData data;
    private int ticksSinceSave;

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        EconomyData stored = server.getDataStorage().get(EconomyData.TYPE);
        // SavedDataStorage liefert für eine unlesbare Datei ebenfalls null. Ohne diese Prüfung würde sie als neue Welt gelten.
        Path file = EconomyData.fileIn(server.getWorldPath(LevelResource.DATA));
        if (stored == null && Files.exists(file)) {
            session.fail();
            IdleRealms.LOGGER.error("Wirtschaft nicht geladen: {} ist vorhanden, aber nicht lesbar; die Datei bleibt unverändert", file);
            return;
        }
        try {
            session.load(stored == null ? null : stored.text(), ZONE_BLOCKED, System.currentTimeMillis());
        } catch (EconomyException e) {
            IdleRealms.LOGGER.error("Wirtschaft nicht geladen; der gespeicherte Stand bleibt unverändert: {}", e.getMessage());
            return;
        }
        if (stored != null && session.readiness() == Readiness.READY) data = stored;
    }

    /**
     * Erstinitialisierung eines Prototypspielstands: legt die Wirtschaft an und speichert sie sofort. Nur ohne gespeicherten
     * Stand zulässig; ein zweiter Aufruf wirft, weil die Sitzung dann nicht mehr ABSENT ist.
     */
    public void initialize(MinecraftServer server, UUID realmId) {
        session.create(realmId, System.currentTimeMillis(), ZONE_BLOCKED);
        EconomyData created = new EconomyData(encodedState());
        server.getDataStorage().set(EconomyData.TYPE, created);
        data = created;
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (data == null) return;
        try {
            session.advanceTo(System.currentTimeMillis());
        } catch (EconomyException e) {
            IdleRealms.LOGGER.error("Schritt verworfen, der Wirtschaftsstand bleibt unverändert: {}", e.getMessage());
            return;
        }
        if (++ticksSinceSave >= SAVE_INTERVAL_TICKS) {
            ticksSinceSave = 0;
            adoptChanges();
            // Der Stand ist bereits übernommen; nur das Schreiben läuft asynchron.
            event.getServer().getDataStorage().scheduleSave();
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (data != null) {
            adoptChanges();
            // Wartet auch auf noch laufende asynchrone Speicherungen.
            event.getServer().getDataStorage().saveAndJoin();
        }
        data = null;
        ticksSinceSave = 0;
        session.unload();
    }

    /** Übernimmt den aktuellen Stand nur, wenn er sich seit der letzten Übernahme geändert hat. */
    private void adoptChanges() {
        String current = encodedState();
        if (current != null && !current.equals(data.text())) data.adopt(current);
    }

    private String encodedState() {
        return session.snapshot().map(EconomySnapshotText::encode).orElse(null);
    }
}
