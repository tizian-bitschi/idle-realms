package idle_realms.server;

import org.jspecify.annotations.Nullable;

/** Speicher des Wirtschaftsstands. Die Welt-Implementierung ist {@link SavedDataEconomyStore}; Tests nutzen eine eigene. */
interface EconomyStore {
    /** Gespeicherter Stand oder null, wenn kein lesbarer Stand vorliegt. */
    @Nullable EconomyData get();

    /** Ob die Datei des Stands existiert, auch wenn sie nicht lesbar ist. */
    boolean fileExists();

    /** Legt den Stand an und markiert ihn zum Speichern. */
    void set(EconomyData data);

    /** Plant das Speichern geänderter Daten; das Schreiben läuft asynchron. */
    void scheduleSave();

    /** Speichert geänderte Daten und wartet auf das Ende der Schreibvorgänge. */
    void saveAndJoin();
}
