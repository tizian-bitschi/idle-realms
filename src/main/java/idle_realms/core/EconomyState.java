package idle_realms.core;

import idle_realms.core.EconomyException.Reason;
import idle_realms.core.StepReport.Status;
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
 * und erzeugt keine Welt-Objekte.
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

    private final UUID realmId;
    private final long epoch;
    private final EnumMap<Resource, Long> stock = new EnumMap<>(Resource.class);
    private final EnumMap<Resource, Long> capacity = new EnumMap<>(Resource.class);
    private final Set<Resource> storageDone = EnumSet.noneOf(Resource.class);
    private final Set<Plot> unlocked = EnumSet.of(Plot.P1, Plot.P2, Plot.P3, Plot.P4);
    /** Betriebsregister nach ID; die Verarbeitung in ID-Reihenfolge macht die Verteilung deterministisch. */
    private final TreeMap<Integer, Building> buildings = new TreeMap<>();
    /** Ausstehende Befehle in Sequenzreihenfolge. */
    private final List<Pending> pending = new ArrayList<>();
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

    /** Nimmt einen Befehl zur Systemzeit now an. Er wirkt am ersten Schrittbeginn ab now, nie vor dem Anker. */
    public void command(long now, Command command) {
        long at = Math.max(epoch + Math.ceilDiv(now - epoch, STEP_MS) * STEP_MS, anchor);
        pending.add(new Pending(at, nextSeq++, command));
        revision++;
    }

    /** Rechnet alle vollständigen Schritte bis zur Systemzeit now nach. Abwesenheit wird auf acht Stunden begrenzt. */
    public List<StepReport> advanceTo(long now) {
        long earliest = epoch + Math.ceilDiv(now - MAX_ABSENCE_MS - epoch, STEP_MS) * STEP_MS;
        if (anchor < earliest) {
            anchor = earliest;
            pending.replaceAll(p -> new Pending(Math.max(p.at(), anchor), p.seq(), p.command()));
        }
        List<StepReport> reports = new ArrayList<>();
        while (anchor + STEP_MS <= now) {
            reports.add(step());
        }
        return reports;
    }

    /** Ein Schritt mit Beginn am Anker. Eine verletzte Vorbedingung bricht ihn ab, bevor etwas geändert wird. */
    private StepReport step() {
        for (Resource r : Resource.values()) {
            if (stock.get(r) > capacity.get(r)) throw new EconomyException(Reason.STOCK_ABOVE_CAPACITY, r + " über der Kapazität");
        }
        long b = anchor;
        List<Pending> due = pending.stream().filter(p -> p.at() == b).toList();
        pending.removeAll(due);
        List<StepReport.Applied> applied = new ArrayList<>();
        for (Pending p : due) {
            applied.add(new StepReport.Applied(p.command(), apply(p.command(), b)));
        }
        for (Building bd : buildings.values()) {
            if (bd.pendingStage > 0 && bd.pendingFrom <= b) {
                bd.stage = bd.pendingStage;
                bd.pendingStage = 0;
            }
        }
        // Eingang und freier Ausgang gelten zu Schrittbeginn; jeder Betrieb verringert sie für die folgenden.
        EnumMap<Resource, Long> avail = new EnumMap<>(stock);
        EnumMap<Resource, Long> free = new EnumMap<>(Resource.class);
        EnumMap<Resource, Long> delta = new EnumMap<>(Resource.class);
        for (Resource r : Resource.values()) {
            free.put(r, capacity.get(r) - stock.get(r));
            delta.put(r, 0L);
        }
        List<StepReport.Row> rows = new ArrayList<>();
        for (Building bd : buildings.values()) {
            rows.add(produce(bd, b, avail, free, delta));
        }
        for (Resource r : Resource.values()) {
            stock.put(r, stock.get(r) + delta.get(r));
        }
        anchor += STEP_MS;
        revision++;
        return new StepReport(b, applied, rows, Map.copyOf(stock));
    }

    private StepReport.Row produce(Building bd, long b, EnumMap<Resource, Long> avail,
            EnumMap<Resource, Long> free, EnumMap<Resource, Long> delta) {
        if (bd.paused) return new StepReport.Row(bd.id, Status.PAUSED, false, 0, 0);
        if (b < bd.activeFrom) return new StepReport.Row(bd.id, Status.RUNNING, true, 0, 0);
        BuildingKind k = bd.kind;
        long outRate = k.outputRate(bd.stage);
        long consumed = 0;
        long produced;
        if (k.input() == null) {
            produced = Math.min(outRate, free.get(k.output()));
        } else {
            consumed = Math.min(Math.min(outRate / k.ratio(), avail.get(k.input())), free.get(k.output()) / k.ratio());
            produced = consumed * k.ratio();
            avail.merge(k.input(), -consumed, Long::sum);
            delta.merge(k.input(), -consumed, Long::sum);
        }
        free.merge(k.output(), -produced, Long::sum);
        delta.merge(k.output(), produced, Long::sum);
        Status status = Status.RUNNING;
        if (produced == 0) {
            status = free.get(k.output()) < k.ratio() ? Status.OUTPUT_FULL : Status.MISSING_INPUT;
        }
        return new StepReport.Row(bd.id, status, false, produced, consumed);
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

    /** Betrieb im Register. Paketsichtbar, damit Tests Zustände direkt aufbauen können. */
    static final class Building {
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

    private record Pending(long at, long seq, Command command) {}
}
