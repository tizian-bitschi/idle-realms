package idle_realms.core;

/** Betrieb im Register. Paketsichtbar, damit der Kern Zustände verwalten und Tests sie aufbauen können. */
final class Building {
    final int id;
    final BuildingKind kind;
    Plot plot;
    int stage;
    boolean paused;
    /** 0 bedeutet kein Stufenwechsel ausstehend. */
    int pendingStage;
    long pendingFrom;
    /** Beginn der Produktion; davor läuft der Anlauf. */
    long activeFrom;

    Building(int id, BuildingKind kind, Plot plot, int stage, boolean paused, long activeFrom) {
        this.id = id;
        this.kind = kind;
        this.plot = plot;
        this.stage = stage;
        this.paused = paused;
        this.activeFrom = activeFrom;
    }
}
