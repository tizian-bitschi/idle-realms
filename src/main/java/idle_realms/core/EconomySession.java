package idle_realms.core;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Die eine Wirtschaft eines Prototypspielstands über den Welt-Lebenszyklus (Issue #7). Befehle und Fortschritt gibt es nur im
 * Zustand {@link Readiness#READY}, also nach vollständigem Laden und erfolgreicher Nachberechnung bis zur aktuellen Zeit. Eine
 * zweite Wirtschaft in derselben Sitzung wird abgewiesen; erst {@link #unload()} gibt die Sitzung wieder frei.
 */
public final class EconomySession {
    /** NOT_LOADED vor dem Laden, ABSENT ohne gespeicherten Stand, READY nach Laden oder Anlegen, FAILED bei Ladefehler. */
    public enum Readiness {
        NOT_LOADED,
        ABSENT,
        READY,
        FAILED
    }

    private Readiness readiness = Readiness.NOT_LOADED;
    private EconomyState state;
    private CommandHandler handler;

    public synchronized Readiness readiness() {
        return readiness;
    }

    /**
     * Stellt den gespeicherten Stand wieder her und rechnet bis now nach. text null bedeutet: kein Prototypstand vorhanden. Bei
     * einem Fehler wird die Sitzung FAILED, der Text bleibt unberührt und die Ausnahme geht an den Aufrufer.
     */
    public synchronized void load(String text, PlotZone zone, long now) {
        if (readiness != Readiness.NOT_LOADED) throw new IllegalStateException("Wirtschaft ist bereits geladen: " + readiness);
        if (text == null) {
            readiness = Readiness.ABSENT;
            return;
        }
        try {
            EconomyState loaded = EconomyState.restore(EconomySnapshotText.decode(text), zone);
            CommandHandler resumed = new CommandHandler(loaded);
            resumed.advanceTo(now);
            state = loaded;
            handler = resumed;
            readiness = Readiness.READY;
        } catch (EconomyException e) {
            readiness = Readiness.FAILED;
            throw e;
        }
    }

    /** Sperrt die Sitzung, wenn der gespeicherte Stand nicht lesbar ist. Die Quelle bleibt dabei unangetastet. */
    public synchronized void fail() {
        if (readiness != Readiness.NOT_LOADED) throw new IllegalStateException("Sperren nur vor dem Laden: " + readiness);
        readiness = Readiness.FAILED;
    }

    /** Legt die Wirtschaft eines neuen Prototypspielstands an. Nur ohne gespeicherten Stand zulässig. */
    public synchronized void create(UUID realmId, long epoch, PlotZone zone) {
        if (readiness != Readiness.ABSENT) throw new IllegalStateException("Anlegen nur ohne gespeicherten Stand: " + readiness);
        state = EconomyState.start(realmId, epoch, zone);
        handler = new CommandHandler(state);
        readiness = Readiness.READY;
    }

    /** Gibt die Sitzung frei, etwa beim Stoppen des Servers. Danach ist wieder ein Laden möglich. */
    public synchronized void unload() {
        state = null;
        handler = null;
        readiness = Readiness.NOT_LOADED;
    }

    /** Befehlsweg; leer, solange die Wirtschaft nicht bereit ist. */
    public synchronized Optional<CommandHandler> commands() {
        return Optional.ofNullable(handler);
    }

    /** Rechnet die Schritte bis now nach. Außerhalb von READY passiert nichts. */
    public synchronized List<StepReport> advanceTo(long now) {
        return handler == null ? List.of() : handler.advanceTo(now);
    }

    /** Textform des aktuellen Stands für die Speicherung; leer, solange die Wirtschaft nicht bereit ist. */
    public synchronized Optional<String> snapshotText() {
        if (state == null) return Optional.empty();
        synchronized (state) {
            return Optional.of(EconomySnapshotText.encode(state.snapshot()));
        }
    }
}
