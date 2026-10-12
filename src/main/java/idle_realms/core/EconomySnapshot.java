package idle_realms.core;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Vollständiger Wirtschaftszustand als unveränderlicher Wert. Der Adapter speichert ihn ohne Zugriff auf interne Felder und
 * stellt ihn mit {@link EconomyState#restore} wieder her. Der Kern kennt weder Dimension noch Weltkoordinaten: Die Zuordnung
 * von Bauplätzen zur Welt und ein Weltensprung liegen beim Adapter, nicht bei diesem Snapshot.
 */
public record EconomySnapshot(UUID realmId, long epoch, long anchor, long revision,
        Map<Resource, Long> stock, Map<Resource, Long> capacity, Set<Resource> storageDone, Set<Plot> unlockedPlots,
        List<EconomyState.BuildingView> buildings, List<EconomyState.PendingCommand> pending, long nextSeq, int nextId) {

    public EconomySnapshot {
        stock = Map.copyOf(stock);
        capacity = Map.copyOf(capacity);
        storageDone = Set.copyOf(storageDone);
        unlockedPlots = Set.copyOf(unlockedPlots);
        buildings = List.copyOf(buildings);
        pending = List.copyOf(pending);
    }
}
