package idle_realms.server;

import idle_realms.IdleRealms;
import idle_realms.core.EconomyException;
import idle_realms.core.EconomySession;
import idle_realms.core.EconomySession.Readiness;
import idle_realms.core.PlotZone;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Bindet die Wirtschaft an den Server-Lebenszyklus (Issue #7): laden beim Start, fortschreiben pro Tick, speichern und entladen
 * beim Stoppen. Chunks spielen keine Rolle; Betriebe bleiben im Register, auch wenn ihre Chunks entladen sind.
 */
public final class EconomyLifecycle {
    // shortcut: Bauzone wird nicht gegen Spielerblöcke geprüft, bis der Befehl Bauplätze freigibt; bis dahin nimmt kein Befehl Aufträge an
    private static final PlotZone ZONE_FREE = plot -> true;
    /** Obergrenze für den Verlust bei einem Absturz zwischen zwei Speicherungen: 30 Sekunden. */
    private static final int SAVE_INTERVAL_TICKS = 30 * 20;

    private final EconomySession session = new EconomySession();
    private EconomyData data;
    private int ticksSinceSave;

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        EconomyData stored = event.getServer().getDataStorage().get(EconomyData.TYPE);
        // SavedDataStorage liefert für eine unlesbare Datei ebenfalls null. Ohne diese Prüfung würde sie als neue Welt gelten.
        Path file = EconomyData.fileIn(event.getServer().getWorldPath(LevelResource.DATA));
        if (stored == null && Files.exists(file)) {
            session.fail();
            IdleRealms.LOGGER.error("Wirtschaft nicht geladen: {} ist vorhanden, aber nicht lesbar; die Datei bleibt unverändert", file);
            return;
        }
        try {
            session.load(stored == null ? null : stored.loaded(), ZONE_FREE, System.currentTimeMillis());
        } catch (EconomyException e) {
            IdleRealms.LOGGER.error("Wirtschaft nicht geladen; der gespeicherte Stand bleibt unverändert: {}", e.getMessage());
            return;
        }
        if (stored != null && session.readiness() == Readiness.READY) {
            stored.attach(session);
            data = stored;
        }
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
        data.setDirty();
        if (++ticksSinceSave >= SAVE_INTERVAL_TICKS) {
            ticksSinceSave = 0;
            // Der Text entsteht sofort auf dem Serverthread; nur das Schreiben läuft asynchron.
            event.getServer().getDataStorage().scheduleSave();
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (data != null) {
            // Vor dem Entladen speichern, damit der Stand des letzten Ticks im Snapshot steht.
            event.getServer().getDataStorage().saveAndJoin();
        }
        data = null;
        ticksSinceSave = 0;
        session.unload();
    }
}
