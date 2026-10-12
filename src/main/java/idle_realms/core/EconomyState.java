package idle_realms.core;

import idle_realms.core.EconomyException.Reason;
import idle_realms.core.StepReport.Status;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Autoritativer Wirtschaftszustand von M0 (ADR 0002). Die Zeit kommt als Parameter herein; die Logik liest keine Systemuhr
 * und erzeugt keine Welt-Objekte. Schreibende Methoden sind paketsichtbar: Der {@link CommandHandler} ist der einzige
 * Schreibweg von außen. Die Produktionsregel liegt in {@link StepProduction}.
 */
public final class EconomyState {
    public static final long STEP_MS = 1_000;
    /** Untereinheiten je Einheit. */
    public static final long UNIT = 1_000;
    public static final long CAPACITY_START = 1_200 * UNIT;
    public static final long CAPACITY_UPGRADE = 1_200 * UNIT;
    public static final long CAPACITY_MAX = 1_000_000_000L;
    public static final long MAX_ABSENCE_MS = 8 * 3_600 * 1_000L;

    static final Map<Resource, Long> EXPAND_COST =
            Map.of(Resource.WOOD, 120 * UNIT, Resource.BOARDS, 40 * UNIT);
    static final Map<Resource, Map<Resource, Long>> STORAGE_COST = Map.of(
            Resource.WOOD, Map.of(Resource.WOOD, 40 * UNIT),
            Resource.BOARDS, Map.of(Resource.WOOD, 60 * UNIT));

    /** Ablehnungsgrund eines Befehls; der Zustand bleibt dabei unverändert. */
    public enum Rejection {
        PLOT_LOCKED,
        PLOT_RESERVED,
        PLOT_OCCUPIED,
        ZONE_BLOCKED,
        MAX_COUNT,
        COST,
        UNKNOWN_BUILDING,
        STAGE,
        MAX_STAGE,
        STALE_STAGE,
        STALE_DEFINITION,
        WRONG_REALM,
        NOT_PAUSED,
        STORAGE_DONE,
        CAPACITY_LIMIT,
        ALREADY_UNLOCKED,
        ID_EXHAUSTED
    }

    /** Stand eines angenommenen Auftrags: eingereiht, angewandt oder beim Schrittbeginn abgelehnt. */
    public enum RequestStatus {
        QUEUED,
        APPLIED,
        REJECTED
    }

    /** Endstand eines Auftrags; rejection ist nur bei REJECTED gesetzt. */
    public record RequestRecord(RequestStatus status, Rejection rejection) {}

    /** Lesesicht auf einen Betrieb; zugleich der Betriebseintrag im Snapshot. last ist null vor dem ersten Schritt. */
    public record BuildingView(int id, BuildingKind kind, Plot plot, int stage, boolean paused,
            int pendingStage, long pendingFrom, long activeFrom, StepReport.Row last) {}

    /**
     * Ausstehender Befehl mit Schrittbeginn und Sequenznummer. requestId ist null ohne Auftrag; expectedStage gilt nur für
     * Upgrade und wird am Schrittbeginn gegen die aktuelle Stufe geprüft.
     */
    public record PendingCommand(long at, long seq, Command command, UUID requestId, int expectedStage) {}

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
    /** Stand aller angenommenen Aufträge; bleibt nach der Ausführung erhalten, damit eine Wiederholung nichts doppelt kauft. */
    // shortcut: Auftragsstände wachsen unbegrenzt, Aufbewahrungsfrist vor produktivem Dauerbetrieb festlegen
    private final Map<UUID, RequestRecord> requests = new HashMap<>();
    private final PlotZone zone;
    private long anchor;
    private long revision;
    private long nextSeq = 1;
    private int nextId;

    EconomyState(UUID realmId, long epoch, Map<Resource, Long> stock, Map<Resource, Long> capacity, List<Building> buildings,
            PlotZone zone) {
        this.realmId = realmId;
        this.epoch = epoch;
        this.zone = Objects.requireNonNull(zone, "zone");
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
    public static EconomyState start(UUID realmId, long epoch, PlotZone zone) {
        return new EconomyState(realmId, epoch, Map.of(), Map.of(),
                List.of(new Building(1, BuildingKind.FOREST, Plot.P1, 1, false, epoch)), zone);
    }

    /**
     * Stellt einen mit {@link #snapshot()} erzeugten Zustand wieder her. Jeder Snapshot wird vollständig geprüft; ein
     * widersprüchlicher Snapshot wird abgewiesen und erzeugt keinen Zustand.
     */
    public static EconomyState restore(EconomySnapshot s, PlotZone zone) {
        if (s.anchor() < s.epoch() || Math.floorMod(s.anchor() - s.epoch(), STEP_MS) != 0) {
            throw invalidSnapshot("Anker liegt nicht auf dem Schrittraster");
        }
        if (s.revision() < 0 || s.revision() == Long.MAX_VALUE) throw invalidSnapshot("Revision außerhalb des Bereichs");
        if (s.nextSeq() < 1 || s.nextSeq() == Long.MAX_VALUE) throw invalidSnapshot("nächste Sequenz außerhalb des Bereichs");
        if (s.nextId() < 1 || s.nextId() >= Integer.MAX_VALUE) throw invalidSnapshot("nächste ID außerhalb des Bereichs");
        for (Resource r : Resource.values()) {
            if (!s.stock().containsKey(r) || !s.capacity().containsKey(r)) {
                throw invalidSnapshot("Bestand oder Kapazität von " + r + " fehlt");
            }
            // Lagerausbau erhöht die Kapazität genau einmal; Ausbau und Kapazität müssen zusammenpassen.
            long expectedCapacity = CAPACITY_START + (s.storageDone().contains(r) ? CAPACITY_UPGRADE : 0);
            if (s.capacity().get(r) != expectedCapacity) throw invalidSnapshot("Kapazität von " + r + " passt nicht zum Lagerausbau");
            if (s.stock().get(r) > s.capacity().get(r)) throw invalidSnapshot(r + " über der Kapazität");
        }
        if (!s.unlockedPlots().containsAll(EnumSet.of(Plot.P1, Plot.P2, Plot.P3, Plot.P4))) {
            throw invalidSnapshot("Startbauplätze fehlen");
        }
        requireConsistentRequests(s);
        long previousSeq = 0;
        for (PendingCommand p : s.pending()) {
            if (!isValid(p.command())) throw invalidSnapshot("ungültiger Befehl mit Sequenz " + p.seq());
            if (p.at() < s.anchor() || Math.floorMod(p.at() - s.epoch(), STEP_MS) != 0) {
                throw invalidSnapshot("Schrittzeit des Befehls " + p.seq() + " liegt nicht auf dem Raster");
            }
            if (p.seq() <= previousSeq || p.seq() >= s.nextSeq()) {
                throw invalidSnapshot("Sequenz " + p.seq() + " ist nicht fortlaufend sortiert");
            }
            previousSeq = p.seq();
        }
        requireConsistentBuildings(s);

        EconomyState st = new EconomyState(s.realmId(), s.epoch(), s.stock(), s.capacity(), toBuildings(s.buildings()), zone);
        st.anchor = s.anchor();
        st.revision = s.revision();
        st.nextSeq = s.nextSeq();
        st.nextId = s.nextId();
        st.storageDone.addAll(s.storageDone());
        st.unlocked.clear();
        st.unlocked.addAll(s.unlockedPlots());
        st.pending.addAll(s.pending());
        st.requests.putAll(s.requests());
        return st;
    }

    /** Vollständiger Zustand als Wert; Änderungen am Ergebnis wirken nicht zurück. */
    public EconomySnapshot snapshot() {
        return new EconomySnapshot(realmId, epoch, anchor, revision, stock, capacity, storageDone, unlocked,
                buildings(), pending, nextSeq, nextId, requests);
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
                .map(b -> new BuildingView(b.id, b.kind, b.plot, b.stage, b.paused, b.pendingStage, b.pendingFrom,
                        b.activeFrom, b.last))
                .toList();
    }

    public Set<Plot> unlockedPlots() {
        return EnumSet.copyOf(unlocked);
    }

    /** Stand eines Auftrags, null wenn die Kennung unbekannt ist. */
    RequestRecord requestStatus(UUID requestId) {
        return requests.get(requestId);
    }

    /** Reiht einen Befehl ohne Auftrag ein; die Zielstufe wird auf die aktuelle Stufe + 1 gesetzt. */
    void command(long now, Command command) {
        command(now, null, expectedStageNow(command), command);
    }

    /** Reiht einen Auftrag ein. Er wirkt am ersten Schrittbeginn ab now, nie vor dem Anker. Doppelte Kennungen werden abgewiesen. */
    void command(long now, UUID requestId, int expectedStage, Command command) {
        validate(command);
        if (requestId != null && requests.containsKey(requestId)) {
            throw new EconomyException(Reason.INVALID_COMMAND, "Auftrag " + requestId + " ist bereits eingereiht");
        }
        long at = Math.max(stepAtOrAfter(now, 0), anchor);
        long nextRevision = incremented(revision);
        long seqAfter = incremented(nextSeq);
        pending.add(new PendingCommand(at, nextSeq, command, requestId, expectedStage));
        if (requestId != null) requests.put(requestId, new RequestRecord(RequestStatus.QUEUED, null));
        nextSeq = seqAfter;
        revision = nextRevision;
    }

    /** Rechnet alle vollständigen Schritte bis zur Systemzeit now nach. Abwesenheit wird auf acht Stunden begrenzt. */
    List<StepReport> advanceTo(long now) {
        // Vor jeder Änderung des Ankers prüfen, damit eine Verletzung den Zustand nicht verändert.
        requireStocksWithinCapacity();
        // Negative Differenz: kein Schritt, und der Anker bleibt unverändert (ADR 0002, Abschnitt 4.4).
        if (now < anchor) return List.of();
        long earliest = stepAtOrAfter(now, MAX_ABSENCE_MS);
        // Der Zähler muss für alle Schritte reichen, bevor der Anker verschoben oder ein Schritt berechnet wird.
        long start = Math.max(anchor, earliest);
        long steps = now - start >= STEP_MS ? (now - start) / STEP_MS : 0;
        if (steps > Long.MAX_VALUE - 1 - revision) {
            throw new EconomyException(Reason.COUNTER_OVERFLOW, "Revision für " + steps + " Schritte zu groß");
        }
        if (anchor < earliest) {
            anchor = earliest;
            pending.replaceAll(p -> new PendingCommand(Math.max(p.at(), anchor), p.seq(), p.command(), p.requestId(),
                    p.expectedStage()));
        }
        List<StepReport> reports = new ArrayList<>();
        // Der Anker liegt nach dem Verschieben höchstens 8 Stunden vor now; die Differenz läuft nicht über.
        while (now - anchor >= STEP_MS) {
            reports.add(step());
        }
        return reports;
    }

    /**
     * Prüft alle Voraussetzungen gegen den aktuellen Zustand. Vorschau und Ausführung am Schrittbeginn verwenden dieselbe
     * Prüfung; null bedeutet, dass der Befehl ausführbar ist.
     */
    Rejection check(Command command, int expectedStage) {
        return switch (command) {
            case Command.Build c -> buildCheck(c);
            case Command.Upgrade c -> upgradeCheck(c.buildingId(), expectedStage);
            case Command.Pause c -> unknown(c.buildingId());
            case Command.Resume c -> {
                Rejection unknown = unknown(c.buildingId());
                yield unknown != null ? unknown : buildings.get(c.buildingId()).paused ? null : Rejection.NOT_PAUSED;
            }
            case Command.Move c -> moveCheck(c);
            case Command.Storage c -> storageCheck(c.resource());
            case Command.Expand c -> expandCheck();
        };
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

    /** Zähler wachsen höchstens bis Long.MAX_VALUE - 1. So entsteht nie ein Zustand, den restore() nicht annimmt. */
    private static long incremented(long counter) {
        if (counter >= Long.MAX_VALUE - 1) throw new EconomyException(Reason.COUNTER_OVERFLOW, "Zähler " + counter);
        return counter + 1;
    }

    /** Ungültige Parameter werden vor der Warteschlange abgewiesen, damit die Schrittverarbeitung nicht abbricht. */
    static void validate(Command command) {
        if (!isValid(command)) throw new EconomyException(Reason.INVALID_COMMAND, String.valueOf(command));
    }

    private static boolean isValid(Command command) {
        if (command == null) return false;
        return switch (command) {
            case Command.Build c -> c.kind() != null && c.plot() != null;
            case Command.Move c -> c.plot() != null;
            case Command.Storage c -> c.resource() != null;
            default -> true;
        };
    }

    /** Zielstufe eines Befehls ohne Auftrag: die aktuelle Stufe + 1 bei Upgrade, sonst ungeprüft (0). */
    private int expectedStageNow(Command command) {
        if (command instanceof Command.Upgrade u && buildings.containsKey(u.buildingId())) {
            return buildings.get(u.buildingId()).stage + 1;
        }
        return 0;
    }

    /** Ein Schritt mit Beginn am Anker. Befehle werden erst nach ihrer Prüfung aus der Warteschlange entfernt. */
    private StepReport step() {
        long nextRevision = incremented(revision);
        long b = anchor;
        List<PendingCommand> due = pending.stream().filter(p -> p.at() == b).toList();
        List<StepReport.Applied> applied = new ArrayList<>();
        for (PendingCommand p : due) {
            Rejection rejection = check(p.command(), p.expectedStage());
            if (rejection == null) apply(p.command(), b);
            applied.add(new StepReport.Applied(p.requestId(), p.command(), rejection));
            if (p.requestId() != null) {
                requests.put(p.requestId(), new RequestRecord(rejection == null ? RequestStatus.APPLIED : RequestStatus.REJECTED,
                        rejection));
            }
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
            StepReport.Row row = production.produce(bd, b);
            bd.last = row;
            rows.add(row);
        }
        for (Resource r : Resource.values()) {
            stock.put(r, stock.get(r) + production.delta(r));
        }
        anchor += STEP_MS;
        revision = nextRevision;
        return new StepReport(b, applied, rows, stock);
    }

    /** Wendet einen geprüften Befehl an. Die Prüfung ist vorher erfolgt; hier wird nur geändert. */
    private void apply(Command command, long b) {
        switch (command) {
            case Command.Build c -> build(c, b);
            case Command.Upgrade c -> upgrade(c.buildingId(), b);
            case Command.Pause c -> buildings.get(c.buildingId()).paused = true;
            case Command.Resume c -> buildings.get(c.buildingId()).paused = false;
            case Command.Move c -> move(c, b);
            case Command.Storage c -> storage(c.resource());
            case Command.Expand c -> expand();
        }
    }

    private Rejection buildCheck(Command.Build c) {
        Rejection plot = plotRejection(c.plot(), c.kind());
        if (plot != null) return plot;
        if (count(c.kind()) >= c.kind().maxCount()) return Rejection.MAX_COUNT;
        // nextId bleibt höchstens Integer.MAX_VALUE - 1, damit jeder Zustand wiederherstellbar bleibt.
        if (nextId >= Integer.MAX_VALUE - 1) return Rejection.ID_EXHAUSTED;
        return affordable(c.kind().buildCost()) ? null : Rejection.COST;
    }

    private void build(Command.Build c, long b) {
        pay(c.kind().buildCost());
        int id = nextId++;
        buildings.put(id, new Building(id, c.kind(), c.plot(), 1, false, b + STEP_MS));
    }

    private Rejection upgradeCheck(int id, int expectedStage) {
        Building bd = buildings.get(id);
        if (bd == null) return Rejection.UNKNOWN_BUILDING;
        if (bd.stage >= bd.kind.stages()) return Rejection.MAX_STAGE;
        if (bd.pendingStage > 0) return Rejection.STAGE;
        if (expectedStage != bd.stage + 1) return Rejection.STALE_STAGE;
        return affordable(bd.kind.upgradeCost()) ? null : Rejection.COST;
    }

    private void upgrade(int id, long b) {
        Building bd = buildings.get(id);
        pay(bd.kind.upgradeCost());
        bd.pendingStage = bd.stage + 1;
        bd.pendingFrom = b + STEP_MS;
    }

    private Rejection moveCheck(Command.Move c) {
        Building bd = buildings.get(c.buildingId());
        if (bd == null) return Rejection.UNKNOWN_BUILDING;
        return plotRejection(c.plot(), bd.kind);
    }

    private void move(Command.Move c, long b) {
        Building bd = buildings.get(c.buildingId());
        bd.plot = c.plot();
        bd.activeFrom = b + STEP_MS;
    }

    private Rejection storageCheck(Resource resource) {
        if (storageDone.contains(resource)) return Rejection.STORAGE_DONE;
        if (capacity.get(resource) + CAPACITY_UPGRADE > CAPACITY_MAX) return Rejection.CAPACITY_LIMIT;
        return affordable(STORAGE_COST.get(resource)) ? null : Rejection.COST;
    }

    private void storage(Resource resource) {
        pay(STORAGE_COST.get(resource));
        storageDone.add(resource);
        capacity.merge(resource, CAPACITY_UPGRADE, Long::sum);
    }

    private Rejection expandCheck() {
        if (unlocked.contains(Plot.E1)) return Rejection.ALREADY_UNLOCKED;
        return affordable(EXPAND_COST) ? null : Rejection.COST;
    }

    private void expand() {
        pay(EXPAND_COST);
        unlocked.add(Plot.E1);
    }

    private Rejection unknown(int id) {
        return buildings.containsKey(id) ? null : Rejection.UNKNOWN_BUILDING;
    }

    /** Bauplatz für Bau und Versetzen: freigeschaltet, für den Betrieb zugelassen, unbesetzt und in der Welt frei. */
    private Rejection plotRejection(Plot plot, BuildingKind kind) {
        if (!unlocked.contains(plot)) return Rejection.PLOT_LOCKED;
        if (plot.reservedFor() != null && plot.reservedFor() != kind) return Rejection.PLOT_RESERVED;
        if (buildings.values().stream().anyMatch(x -> x.plot == plot)) return Rejection.PLOT_OCCUPIED;
        if (!zone.isFree(plot)) return Rejection.ZONE_BLOCKED;
        return null;
    }

    private boolean affordable(Map<Resource, Long> cost) {
        return cost.entrySet().stream().allMatch(e -> stock.get(e.getKey()) >= e.getValue());
    }

    /** Bucht die Kosten ab; wird nur nach erfolgreicher Prüfung aufgerufen. */
    private void pay(Map<Resource, Long> cost) {
        cost.forEach((r, v) -> stock.merge(r, -v, Long::sum));
    }

    private int count(BuildingKind kind) {
        return (int) buildings.values().stream().filter(x -> x.kind == kind).count();
    }

    private static List<Building> toBuildings(List<BuildingView> views) {
        return views.stream().map(v -> {
            Building b = new Building(v.id(), v.kind(), v.plot(), v.stage(), v.paused(), v.activeFrom());
            b.pendingStage = v.pendingStage();
            b.pendingFrom = v.pendingFrom();
            b.last = v.last();
            return b;
        }).toList();
    }

    /**
     * Auftragsstände müssen zur Warteschlange passen: Ein eingereihter Auftrag steht in der Warteschlange, ein abgeschlossener
     * nicht. Ein abgelehnter Auftrag trägt einen Grund, alle anderen keinen.
     */
    private static void requireConsistentRequests(EconomySnapshot s) {
        Set<UUID> queued = new HashSet<>();
        for (PendingCommand p : s.pending()) {
            if (p.requestId() == null) continue;
            RequestRecord r = s.requests().get(p.requestId());
            if (r == null || r.status() != RequestStatus.QUEUED) throw invalidSnapshot("Auftrag " + p.requestId() + " ist nicht eingereiht");
            queued.add(p.requestId());
        }
        for (Map.Entry<UUID, RequestRecord> e : s.requests().entrySet()) {
            RequestRecord r = e.getValue();
            if ((r.status() == RequestStatus.REJECTED) != (r.rejection() != null)) {
                throw invalidSnapshot("Ablehnungsgrund von Auftrag " + e.getKey() + " passt nicht zum Status");
            }
            if (r.status() == RequestStatus.QUEUED && !queued.contains(e.getKey())) {
                throw invalidSnapshot("Auftrag " + e.getKey() + " ist ohne Warteschlangeneintrag eingereiht");
            }
        }
    }

    /** Betriebsregister eines Snapshots: Typen, Bauplätze, Stufen, Maximalzahl und letzter Schritt müssen zu den Regeln passen. */
    private static void requireConsistentBuildings(EconomySnapshot s) {
        Set<Plot> unlocked = s.unlockedPlots();
        Set<Integer> ids = new HashSet<>();
        Set<Plot> occupied = EnumSet.noneOf(Plot.class);
        Map<BuildingKind, Integer> counts = new EnumMap<>(BuildingKind.class);
        for (BuildingView v : s.buildings()) {
            if (v.kind() == null || v.plot() == null) throw invalidSnapshot("Betrieb ohne Typ oder Bauplatz");
            if (v.id() < 1 || v.id() >= s.nextId() || !ids.add(v.id())) throw invalidSnapshot("ID von Betrieb " + v.id());
            if (v.stage() < 1 || v.stage() > v.kind().stages()) throw invalidSnapshot("Stufe von Betrieb " + v.id());
            if (v.pendingStage() != 0 && (v.pendingStage() != v.stage() + 1 || v.pendingStage() > v.kind().stages())) {
                throw invalidSnapshot("Stufenwechsel von Betrieb " + v.id());
            }
            // Pausen wirken nur im Schritt; der letzte Schritt muss den Pausenzustand daher widerspiegeln.
            boolean pausedAgrees = v.last() == null ? !v.paused() : v.paused() == (v.last().status() == Status.PAUSED);
            if (!pausedAgrees) throw invalidSnapshot("Pausenzustand von Betrieb " + v.id() + " widerspricht dem letzten Schritt");
            if (!unlocked.contains(v.plot())) throw invalidSnapshot("Betrieb " + v.id() + " auf gesperrtem Bauplatz " + v.plot());
            if (v.plot().reservedFor() != null && v.plot().reservedFor() != v.kind()) {
                throw invalidSnapshot(v.kind() + " auf " + v.plot());
            }
            if (!occupied.add(v.plot())) throw invalidSnapshot("Bauplatz " + v.plot() + " doppelt belegt");
            if (counts.merge(v.kind(), 1, Integer::sum) > v.kind().maxCount()) {
                throw invalidSnapshot(v.kind() + " über der Maximalzahl");
            }
            if (v.last() != null) requireConsistentReport(v);
        }
    }

    /** Der letzte Schritt muss zur Produktionsregel des Betriebs passen; negative oder überhöhte Mengen werden abgewiesen. */
    private static void requireConsistentReport(BuildingView v) {
        StepReport.Row row = v.last();
        BuildingKind k = v.kind();
        if (row.status() == null) throw invalidSnapshot("Status von Betrieb " + v.id() + " fehlt");
        if (row.buildingId() != v.id()) throw invalidSnapshot("letzter Schritt von Betrieb " + v.id());
        if (row.produced() < 0 || row.consumed() < 0 || row.produced() > k.outputRate(v.stage())) {
            throw invalidSnapshot("Produktion von Betrieb " + v.id() + " außerhalb der Regel");
        }
        boolean consistentFlow = k.input() == null
                ? row.consumed() == 0
                : row.produced() == row.consumed() * k.ratio();
        if (!consistentFlow) throw invalidSnapshot("Verbrauch von Betrieb " + v.id() + " passt nicht zur Produktion");
        if (row.startingUp() && (row.status() != Status.RUNNING || row.produced() != 0 || row.consumed() != 0)) {
            throw invalidSnapshot("Anlauf von Betrieb " + v.id());
        }
        if (row.status() == Status.PAUSED && (row.produced() != 0 || row.consumed() != 0)) {
            throw invalidSnapshot("pausierter Betrieb " + v.id() + " mit Produktion");
        }
        // Status und Produktion stammen aus derselben Regel: Produktion heißt RUNNING, RUNNING ohne Anlauf heißt Produktion.
        if (row.produced() > 0 && row.status() != Status.RUNNING) {
            throw invalidSnapshot("Produktion von Betrieb " + v.id() + " ohne Status RUNNING");
        }
        if (row.produced() == 0 && row.status() == Status.RUNNING && !row.startingUp()) {
            throw invalidSnapshot("Status RUNNING von Betrieb " + v.id() + " ohne Produktion");
        }
        if (k.input() == null && row.status() == Status.MISSING_INPUT) {
            throw invalidSnapshot("Betrieb " + v.id() + " ohne Eingang meldet MISSING_INPUT");
        }
    }

    private static EconomyException invalidSnapshot(String detail) {
        return new EconomyException(Reason.INVALID_SNAPSHOT, detail);
    }
}
