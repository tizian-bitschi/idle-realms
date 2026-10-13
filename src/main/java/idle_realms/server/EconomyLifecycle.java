package idle_realms.server;

import idle_realms.IdleRealms;
import idle_realms.core.EconomyException;
import idle_realms.core.EconomySession;
import idle_realms.core.EconomySession.Readiness;
import idle_realms.core.EconomySnapshotText;
import idle_realms.core.PlotZone;
import java.util.UUID;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Bindet die Wirtschaft an den Server-Lebenszyklus (Issue #7): laden beim Start, anlegen bei der Erstinitialisierung,
 * fortschreiben pro Tick, speichern und entladen beim Stoppen. Die Methoden nehmen den Speicher und die Zeit als Parameter,
 * damit die Persistenz ohne laufenden Server geprüft werden kann. Chunks spielen keine Rolle; Betriebe bleiben im Register.
 */
public final class EconomyLifecycle {
    // shortcut: Bauzonen sind noch nicht gegen die Welt geprüft; Bau und Versetzen werden deshalb abgewiesen, bis die Startregion (#9) die Zonen liefert
    private static final PlotZone ZONE_BLOCKED = plot -> false;
    /** Obergrenze für den Verlust bei einem Absturz zwischen zwei Speicherungen: 30 Sekunden. */
    private static final int SAVE_INTERVAL_TICKS = 30 * 20;

    private final EconomySession session = new EconomySession();
    private EconomyStore store;
    private EconomyData data;
    private int ticksSinceSave;

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        var server = event.getServer();
        attach(new SavedDataEconomyStore(server.getDataStorage(), server.getWorldPath(LevelResource.DATA)), System.currentTimeMillis());
    }

    /** Bindet den Speicher und lädt den gespeicherten Stand. Ohne Stand bleibt die Sitzung ABSENT. */
    void attach(EconomyStore store, long now) {
        this.store = store;
        EconomyData stored = store.get();
        // Der Speicher liefert für eine unlesbare Datei ebenfalls null. Ohne diese Prüfung würde sie als neue Welt gelten.
        if (stored == null && store.fileExists()) {
            session.fail();
            IdleRealms.LOGGER.error("Wirtschaft nicht geladen: Datei ist vorhanden, aber nicht lesbar; sie bleibt unverändert");
            return;
        }
        try {
            session.load(stored == null ? null : stored.text(), ZONE_BLOCKED, now);
        } catch (EconomyException e) {
            IdleRealms.LOGGER.error("Wirtschaft nicht geladen; der gespeicherte Stand bleibt unverändert: {}", e.getMessage());
            return;
        }
        if (stored != null && session.readiness() == Readiness.READY) data = stored;
    }

    /**
     * Erstinitialisierung eines Prototypspielstands: legt die Wirtschaft an und speichert sie sofort. Nur ohne gespeicherten
     * Stand zulässig. Ein zweiter Aufruf wirft, weil die Sitzung dann nicht mehr ABSENT ist.
     */
    public void initialize(UUID realmId, long now) {
        if (store == null) throw new IllegalStateException("Kein Speicher angebunden");
        session.create(realmId, now, ZONE_BLOCKED);
        EconomyData created = new EconomyData(encodedState());
        store.set(created);
        data = created;
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        tick(System.currentTimeMillis());
    }

    /** Rechnet bis now nach und speichert in regelmäßigen Abständen, sofern sich der Stand geändert hat. */
    void tick(long now) {
        if (data == null) return;
        try {
            session.advanceTo(now);
        } catch (EconomyException e) {
            IdleRealms.LOGGER.error("Schritt verworfen, der Wirtschaftsstand bleibt unverändert: {}", e.getMessage());
            return;
        }
        if (++ticksSinceSave >= SAVE_INTERVAL_TICKS) {
            ticksSinceSave = 0;
            adoptChanges();
            // Vanilla löscht das Dirty-Flag vor dem Schreiben. Schlägt das fehl, wird beim nächsten Versuch erneut geschrieben.
            data.setDirty();
            store.scheduleSave();
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        detach();
    }

    /** Speichert den letzten Stand, wartet auf laufende Schreibvorgänge und gibt die Sitzung frei. */
    void detach() {
        if (data != null) {
            adoptChanges();
            data.setDirty();
            // Wartet auch auf noch laufende asynchrone Speicherungen.
            store.saveAndJoin();
        }
        data = null;
        store = null;
        ticksSinceSave = 0;
        session.unload();
    }

    EconomySession session() {
        return session;
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
