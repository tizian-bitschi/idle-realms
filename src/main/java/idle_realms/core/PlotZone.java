package idle_realms.core;

/**
 * Adapter zur Welt: meldet, ob die Bauzone eines Bauplatzes frei von Spielerblöcken ist (ADR 0002, Abschnitt 7.2). Der Kern
 * kennt keine Blöcke; die Prüfung läuft bei jeder Ausführung am Schrittbeginn erneut.
 */
@FunctionalInterface
public interface PlotZone {
    boolean isFree(Plot plot);
}
