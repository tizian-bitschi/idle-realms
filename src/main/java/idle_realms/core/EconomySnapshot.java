package idle_realms.core;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        if (realmId == null || stock == null || capacity == null || storageDone == null || unlockedPlots == null
                || buildings == null || pending == null) {
            throw invalid("Bestandteil fehlt");
        }
        requireNoNullEntries(stock, "Bestand");
        requireNoNullEntries(capacity, "Kapazität");
        requireNoNulls(storageDone, "Lagerausbau");
        requireNoNulls(unlockedPlots, "Bauplätze");
        requireNoNulls(buildings, "Betriebsliste");
        requireNoNulls(pending, "Warteschlange");
        stock = Map.copyOf(stock);
        capacity = Map.copyOf(capacity);
        storageDone = Set.copyOf(storageDone);
        unlockedPlots = Set.copyOf(unlockedPlots);
        buildings = List.copyOf(buildings);
        pending = List.copyOf(pending);
    }

    private static void requireNoNullEntries(Map<?, ?> map, String name) {
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) throw invalid(name + " enthält einen Nullwert");
        }
    }

    private static void requireNoNulls(Collection<?> items, String name) {
        if (items.stream().anyMatch(Objects::isNull)) throw invalid(name + " enthält einen Nullwert");
    }

    private static EconomyException invalid(String detail) {
        return new EconomyException(EconomyException.Reason.INVALID_SNAPSHOT, detail);
    }
}
