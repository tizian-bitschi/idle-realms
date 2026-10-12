package idle_realms.core;

/** Bauplätze der Startregion und der ersten Erweiterung (ADR 0002, Abschnitt 7.1). */
public enum Plot {
    P1(null),
    P2(BuildingKind.SAWMILL),
    P3(null),
    P4(null),
    E1(null);

    private final BuildingKind reservedFor;

    Plot(BuildingKind reservedFor) {
        this.reservedFor = reservedFor;
    }

    /** Einziger Betriebstyp, der hier stehen darf; null bedeutet frei. */
    public BuildingKind reservedFor() {
        return reservedFor;
    }
}
