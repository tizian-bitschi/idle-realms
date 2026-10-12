package idle_realms.core;

import java.util.List;
import java.util.Map;

/** Ergebnis eines berechneten Schritts: angewandte Befehle, Betriebszustand und Vorräte nach dem Schritt. */
public record StepReport(long at, List<Applied> commands, List<Row> rows, Map<Resource, Long> stock) {

    public enum Status {
        RUNNING,
        PAUSED,
        OUTPUT_FULL,
        MISSING_INPUT
    }

    /** rejection ist null, wenn der Befehl angenommen wurde. */
    public record Applied(Command command, EconomyState.Rejection rejection) {}

    /** startingUp: der Betrieb läuft an (Anlauf). Dann ist der Status RUNNING und die Rate 0. */
    public record Row(int buildingId, Status status, boolean startingUp, long produced, long consumed) {}
}
