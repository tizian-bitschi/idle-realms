package idle_realms.core;

import idle_realms.core.EconomyException.Reason;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Autoritativer Wirtschaftszustand von M0 (ADR 0002). Die Zeit kommt als Parameter herein; die Logik liest keine Systemuhr
 * und erzeugt keine Welt-Objekte. Dieser Aggregate-Root ist der einzige Schreibpfad für Vorräte, Kapazitäten und Betriebe;
 * die Produktionsregel liegt in {@link StepProduction}.
 */
public final class EconomyState {
    public static final long STEP_MS = 1_000;
    /** Untereinheiten je Einheit. */
    public static final long UNIT = 1_000;
    public static final long CAPACITY_START = 1_200 * UNIT;
    public static final long CAPACITY_UPGRADE = 1_200 * UNIT;
    public static final long CAPACITY_MAX = 1_000_000_000L;
    public static final long MAX_ABSENCE_MS = 8 * 3_600 * 1_000L;

    private static final Map<Resource, Long> EXPAND_COST =
            Map.of(Resource.WOOD, 120 * UNIT, Resource.BOARDS, 40 * UNIT);
    private static final Map<Resource, Map<Resource, Long>> STORAGE_COST = Map.of(
            Resource.WOOD, Map.of(Resource.WOOD, 40 * UNIT),
            Resource.BOARDS, Map.of(Resource.WOOD, 60 * UNIT));

    /** Ablehnungsgrund eines Befehls; der Zustand bleibt dabei unverändert. */
    public enum Rejection {
        PLOT_LOCKED,
        PLOT_RESERVED,
        PLOT_OCCUPIED,
        MAX_COUNT,
        COST,
        UNKNOWN_BUILDING,
        STAGE,
        NOT_PAUSED,
        STORAGE_DONE,
        CAPACITY_LIMIT,
        ALREADY_UNLOCKED
    }

    /** Lesesicht auf einen Betrieb; zugleich der Betriebseintrag im Snapshot. */
    public record BuildingView(int id, BuildingKind kind, Plot plot, int stage, boolean paused,
            int pendingStage, long pendingFrom, long activeFrom) {}

    /** Ausstehender Befehl mit Schrittbeginn und Sequenznummer. */
    public record PendingCommand(long at, long seq, Command command) {}

    private final UUID realmId;
    private final long epoch;
    private final EnumMap<Resource, Long> stock = new EnumMap<>(Resource.class);
    private final EnumMap<Resource, Long> capacity = new EnumMap<>(Resource.class);
    private final Set<Resource> storageDone = EnumSet.noneOf(Resource.class);
    private final Set<Plot> unlocked = EnumSet.of(Plot.P1, Plot.P2, Plot.P3, Plot.P4);
    /** Betriebsregister nach ID; die Verarbeitung in ID-Reihenfolge macht die Verteilung deterministisch. */
    private final TreeMap<Integer, Building> buildings = new TreeMap<>();
    /** Ausstehende Befehle in Sequenzreihenfolge. */
    private final List<PendingCommand> pending = new ArrayList<>();
    private long anchor;
    private long revision;
    private long nextSeq = 1;
    private int nextId;

    EconomyState(UUID realmId, long epoch, Map<Resource, Long> stock, Map<Resource, Long> capacity, List<Building> buildings) {
        this.realmId = realmId;
        this.epoch = epoch;
        this.anchor = epoch;
        for (Resource r : Resource.values()) {
            long amount = stock.getOrDefault(r, 0L);
            long cap = capacity.getOrDefault(r, CAPACITY_START);
            if (amount < 0) throw new EconomyException(Reason.INVALID_AMOUNT, r + " ist negativ");
            if (cap < 0 || cap > CAPACITY_MAX) throw new EconomyException(Reason.INVALID_AMOUNT, r + " Kapazität außerhalb der Grenzen");
            this.stock.put(r, amount);
            this.capacity.put(r, cap);
        }
        for (Building b : buildings) {
            if (this.buildings.containsKey(b.id)) throw new EconomyException(Reason.INVALID_BUILDING, "doppelte ID " + b.id);
            if (b.stage < 1 || b.stage > b.kind.stages()) throw new EconomyException(Reason.INVALID_BUILDING, "Stufe " + b.stage);
            if (b.plot.reservedFor() != null && b.plot.reservedFor() != b.kind) {
                throw new EconomyException(Reason.INVALID_BUILDING, b.kind + " auf " + b.plot);
            }
            if (this.buildings.values().stream().anyMatch(x -> x.plot == b.plot)) {
                throw new EconomyException(Reason.INVALID_BUILDING, "Bauplatz " + b.plot + " doppelt belegt");
            }
            this.buildings.put(b.id, b);
        }
        this.nextId = this.buildings.isEmpty() ? 1 : this.buildings.lastKey() + 1;
    }

    /** Startzustand: ein Forstbetrieb Stufe 1 auf P1, der ab der Epoche produziert. */
    public static EconomyState start(UUID realmId, long epoch) {
        return new EconomyState(realmId, epoch, Map.of(), Map.of(),
                List.of(new Building(1, BuildingKind.FOREST, Plot.P1, 1, false, epoch)));
    }

    /**
     * Stellt einen mit {@link #snapshot()} erzeugten Zustand wieder her. Ein widersprüchlicher Snapshot wird vollständig
     * abgewiesen; es entsteht kein Zustand.
     */
    public static EconomyState restore(EconomySnapshot s) {
        if (s.anchor() < s.epoch() || Math.floorMod(s.anchor() - s.epoch(), STEP_MS) != 0) {
            throw invalidSnapshot("Anker liegt nicht auf dem Schrittraster");
        }
        if (!s.unlockedPlots().containsAll(EnumSet.of(Plot.P1, Plot.P2, Plot.P3, Plot.P4))) {
            throw invalidSnapshot("Startbauplätze fehlen");
        }
        for (PendingCommand p : s.pending()) {
            if (p.at() < s.anchor()) throw invalidSnapshot("ausstehender Befehl vor dem Anker");
            if (p.seq() >= s.nextSeq()) throw invalidSnapshot("Sequenz nicht fortgeschrieben");
        }
        for (BuildingView v : s.buildings()) {
            if (v.id() >= s.nextId()) throw invalidSnapshot("nächste ID zu klein für Betrieb " + v.id());
            if (v.pendingStage() < 0 || v.pendingStage() > v.kind().stages()) throw invalidSnapshot("Stufenwechsel von Betrieb " + v.id());
        }
        for (Resource r : Resource.values()) {
            if (s.stock().get(r) > s.capacity().get(r)) throw invalidSnapshot(r + " über der Kapazität");
        }

        EconomyState st = new EconomyState(s.realmId(), s.epoch(), s.stock(), s.capacity(), toBuildings(s.buildings()));
        st.anchor = s.anchor();
        st.revision = s.revision();
        st.nextSeq = s.nextSeq();
        st.nextId = s.nextId();
        st.storageDone.addAll(s.storageDone());
        st.unlocked.clear();
        st.unlocked.addAll(s.unlockedPlots());
        st.pending.addAll(s.pending());
        return st;
    }

    /** Vollständiger Zustand als Wert; Änderungen am Ergebnis wirken nicht zurück. */
    public EconomySnapshot snapshot() {
        return new EconomySnapshot(realmId, epoch, anchor, revision, stock, capacity, storageDone, unlocked,
                buildings(), pending, nextSeq, nextId);
    }

    public UUID realmId() {
        return realmId;
    }

    public long revision() {
        return revision;
    }

    /** Beginn des nächsten nicht berechneten Schritts. */
    public long anchor() {
        return anchor;
    }

    public long stock(Resource resource) {
        return stock.get(resource);
    }

    public long capacity(Resource resource) {
        return capacity.get(resource);
    }

    /** Lesesicht auf das Betriebsregister, nach ID sortiert. */
    public List<BuildingView> buildings() {
        return buildings.values().stream()
                .map(b -> new BuildingView(b.id, b.kind, b.plot, b.stage, b.paused, b.pendingStage, b.pendingFrom, b.activeFrom))
                .toList();
    }

    public Set<Plot> unlockedPlots() {
        return EnumSet.copyOf(unlocked);
    }

    /** Nimmt einen Befehl zur Systemzeit now an. Er wirkt am ersten Schrittbeginn ab now, nie vor dem Anker. */
    public void command(long now, Command command) {
        validate(command);
        long at = Math.max(stepAtOrAfter(now, 0), anchor);
        pending.add(new PendingCommand(at, nextSeq++, command));
        revision++;
    }

    /** Rechnet alle vollständigen Schritte bis zur Systemzeit now nach. Abwesenheit wird auf acht Stunden begrenzt. */
    public List<StepReport> advanceTo(long now) {
        // Vor jeder Änderung des Ankers prüfen, damit eine Verletzung den Zustand nicht verändert.
        requireStocksWithinCapacity();
        long earliest = stepAtOrAfter(now, MAX_ABSENCE_MS);
        if (anchor < earliest) {
            anchor = earliest;
            pending.replaceAll(p -> new PendingCommand(Math.max(p.at(), anchor), p.seq(), p.command()));
        }
        List<StepReport> reports = new ArrayList<>();
        // Der Anker liegt nach dem Verschieben höchstens 8 Stunden vor now; die Differenz läuft nicht über.
        while (now - anchor >= STEP_MS) {
            reports.add(step());
        }
        return reports;
    }

    private void requireStocksWithinCapacity() {
        for (Resource r : Resource.values()) {
            if (stock.get(r) > capacity.get(r)) throw new EconomyException(Reason.STOCK_ABOVE_CAPACITY, r + " über der Kapazität");
        }
    }

    /** Erster Schrittbeginn ab t - lead, gerechnet ab der Epoche. Ein Überlauf wird als strukturierter Fehler gemeldet. */
    private long stepAtOrAfter(long t, long lead) {
        try {
            long steps = Math.ceilDiv(Math.subtractExact(Math.subtractExact(t, lead), epoch), STEP_MS);
            long at = Math.addExact(epoch, Math.multiplyExact(steps, STEP_MS));
            Math.addExact(at, STEP_MS); // Platz für die Wirksamkeit ab dem nächsten Schritt
            return at;
        } catch (ArithmeticException e) {
            throw new EconomyException(Reason.TIME_OUT_OF_RANGE, "Systemzeit " + t);
        }
    }

    /** Ungültige Parameter werden vor der Warteschlange abgewiesen, damit die Schrittverarbeitung nicht abbricht. */
    private static void validate(Command command) {
        if (command == null) throw new EconomyException(Reason.INVALID_COMMAND, "fehlt");
        boolean valid = switch (command) {
            case Command.Build c -> c.kind() != null && c.plot() != null;
            case Command.Move c -> c.plot() != null;
            case Command.Storage c -> c.resource() != null;
            default -> true;
        };
        if (!valid) throw new EconomyException(Reason.INVALID_COMMAND, command.toString());
    }

    /** Ein Schritt mit Beginn am Anker. Befehle werden erst nach ihrer Verarbeitung aus der Warteschlange entfernt. */
    private StepReport step() {
        long b = anchor;
        List<PendingCommand> due = pending.stream().filter(p -> p.at() == b).toList();
        List<StepReport.Applied> applied = new ArrayList<>();
        for (PendingCommand p : due) {
            applied.add(new StepReport.Applied(p.command(), apply(p.command(), b)));
        }
        pending.removeAll(due);
        for (Building bd : buildings.values()) {
            if (bd.pendingStage > 0 && bd.pendingFrom <= b) {
                bd.stage = bd.pendingStage;
                bd.pendingStage = 0;
            }
        }
        StepProduction production = new StepProduction(stock, capacity);
        List<StepReport.Row> rows = new ArrayList<>();
        for (Building bd : buildings.values()) {
            rows.add(production.produce(bd, b));
        }
        for (Resource r : Resource.values()) {
            stock.put(r, stock.get(r) + production.delta(r));
        }
        anchor += STEP_MS;
        revision++;
        return new StepReport(b, applied, rows, stock);
    }

    /** Null, wenn der Befehl angenommen wurde. Prüfung vor Änderung: eine Ablehnung lässt den Zustand unberührt. */
    private Rejection apply(Command command, long b) {
        return switch (command) {
            case Command.Build c -> build(c, b);
            case Command.Upgrade c -> upgrade(c.buildingId(), b);
            case Command.Pause c -> setPaused(c.buildingId(), true);
            case Command.Resume c -> setPaused(c.buildingId(), false);
            case Command.Move c -> move(c, b);
            case Command.Storage c -> storage(c.resource());
            case Command.Expand c -> expand();
        };
    }

    private Rejection build(Command.Build c, long b) {
        Rejection plot = plotRejection(c.plot(), c.kind());
        if (plot != null) return plot;
        if (count(c.kind()) >= c.kind().maxCount()) return Rejection.MAX_COUNT;
        if (!pay(c.kind().buildCost())) return Rejection.COST;
        int id = nextId++;
        buildings.put(id, new Building(id, c.kind(), c.plot(), 1, false, b + STEP_MS));
        return null;
    }

    private Rejection upgrade(int id, long b) {
        Building bd = buildings.get(id);
        if (bd == null) return Rejection.UNKNOWN_BUILDING;
        if (bd.stage >= bd.kind.stages() || bd.pendingStage > 0) return Rejection.STAGE;
        if (!pay(bd.kind.upgradeCost())) return Rejection.COST;
        bd.pendingStage = bd.stage + 1;
        bd.pendingFrom = b + STEP_MS;
        return null;
    }

    /** Pausieren ist immer erlaubt; Fortsetzen nur bei pausiertem Betrieb. */
    private Rejection setPaused(int id, boolean paused) {
        Building bd = buildings.get(id);
        if (bd == null) return Rejection.UNKNOWN_BUILDING;
        if (!paused && !bd.paused) return Rejection.NOT_PAUSED;
        bd.paused = paused;
        return null;
    }

    private Rejection move(Command.Move c, long b) {
        Building bd = buildings.get(c.buildingId());
        if (bd == null) return Rejection.UNKNOWN_BUILDING;
        Rejection plot = plotRejection(c.plot(), bd.kind);
        if (plot != null) return plot;
        bd.plot = c.plot();
        bd.activeFrom = b + STEP_MS;
        return null;
    }

    private Rejection storage(Resource resource) {
        if (storageDone.contains(resource)) return Rejection.STORAGE_DONE;
        if (capacity.get(resource) + CAPACITY_UPGRADE > CAPACITY_MAX) return Rejection.CAPACITY_LIMIT;
        if (!pay(STORAGE_COST.get(resource))) return Rejection.COST;
        storageDone.add(resource);
        capacity.merge(resource, CAPACITY_UPGRADE, Long::sum);
        return null;
    }

    private Rejection expand() {
        if (unlocked.contains(Plot.E1)) return Rejection.ALREADY_UNLOCKED;
        if (!pay(EXPAND_COST)) return Rejection.COST;
        unlocked.add(Plot.E1);
        return null;
    }

    private Rejection plotRejection(Plot plot, BuildingKind kind) {
        if (!unlocked.contains(plot)) return Rejection.PLOT_LOCKED;
        if (plot.reservedFor() != null && plot.reservedFor() != kind) return Rejection.PLOT_RESERVED;
        if (buildings.values().stream().anyMatch(x -> x.plot == plot)) return Rejection.PLOT_OCCUPIED;
        return null;
    }

    /** Bucht die Kosten nur ab, wenn der gesamte Betrag vorhanden ist. */
    private boolean pay(Map<Resource, Long> cost) {
        for (Map.Entry<Resource, Long> e : cost.entrySet()) {
            if (stock.get(e.getKey()) < e.getValue()) return false;
        }
        cost.forEach((r, v) -> stock.merge(r, -v, Long::sum));
        return true;
    }

    private int count(BuildingKind kind) {
        return (int) buildings.values().stream().filter(x -> x.kind == kind).count();
    }

    private static List<Building> toBuildings(List<BuildingView> views) {
        return views.stream().map(v -> {
            Building b = new Building(v.id(), v.kind(), v.plot(), v.stage(), v.paused(), v.activeFrom());
            b.pendingStage = v.pendingStage();
            b.pendingFrom = v.pendingFrom();
            return b;
        }).toList();
    }

    private static EconomyException invalidSnapshot(String detail) {
        return new EconomyException(Reason.INVALID_SNAPSHOT, detail);
    }
}
