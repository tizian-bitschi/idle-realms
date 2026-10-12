package idle_realms.core;

import idle_realms.core.EconomyState.BuildingView;
import idle_realms.core.EconomyState.Rejection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Einziger Schreibweg für Befehle des Servers (Issue #6). Aufrufe sind serialisiert. Kosten und Raten kommen aus den
 * Definitionen des Kerns; Werte aus dem Client werden nur als Prüfgrößen verwendet.
 */
public final class CommandHandler {
    /** Revision der preisrelevanten Definitionen. Jede Änderung an Kosten oder Raten erhöht sie. */
    public static final long DEFINITION_REVISION = 1;

    public enum Outcome {
        ACCEPTED,
        DUPLICATE,
        REJECTED
    }

    /** Sofortige Antwort. rejection ist nur bei REJECTED gesetzt; die Ausführung meldet sich im {@link StepReport}. */
    public record Result(Outcome outcome, Rejection rejection, long definitionRevision) {}

    /** Vorschau mit denselben Definitionen wie die Ausführung. rejection ist null, wenn der Auftrag ausführbar wäre. */
    public record Preview(Rejection rejection, long definitionRevision, Map<Resource, Long> cost, Map<Resource, Long> rateDelta) {}

    private final EconomyState state;

    public CommandHandler(EconomyState state) {
        this.state = state;
    }

    /** Nimmt einen Auftrag zur Systemzeit now an. Eine bereits angenommene Request-Kennung wird nicht erneut angewandt. */
    public synchronized Result submit(CommandRequest request, long now) {
        if (!sameRealm(request)) return new Result(Outcome.REJECTED, Rejection.WRONG_REALM, DEFINITION_REVISION);
        if (state.hasRequest(request.requestId())) return new Result(Outcome.DUPLICATE, null, DEFINITION_REVISION);
        Rejection rejection = check(request);
        if (rejection != null) return new Result(Outcome.REJECTED, rejection, DEFINITION_REVISION);
        state.command(now, request.command());
        state.recordRequest(request.requestId());
        return new Result(Outcome.ACCEPTED, null, DEFINITION_REVISION);
    }

    /** Vorschau ohne Änderung. Eine veraltete Preisrevision liefert keine Kosten, sondern den aktuellen Stand. */
    public synchronized Preview preview(CommandRequest request) {
        EconomyState.validate(request.command());
        Rejection rejection = sameRealm(request) ? check(request) : Rejection.WRONG_REALM;
        if (rejection != null) return new Preview(rejection, DEFINITION_REVISION, Map.of(), Map.of());
        return switch (request.command()) {
            case Command.Build c -> new Preview(null, DEFINITION_REVISION, c.kind().buildCost(),
                    rateDelta(c.kind(), 0, c.kind().outputRate(1)));
            case Command.Upgrade c -> {
                BuildingView bd = find(c.buildingId());
                yield new Preview(null, DEFINITION_REVISION, bd.kind().upgradeCost(),
                        rateDelta(bd.kind(), bd.kind().outputRate(bd.stage()), bd.kind().outputRate(bd.stage() + 1)));
            }
            case Command.Pause c -> {
                BuildingView bd = find(c.buildingId());
                yield new Preview(null, DEFINITION_REVISION, Map.of(),
                        rateDelta(bd.kind(), bd.paused() ? 0 : bd.kind().outputRate(bd.stage()), 0));
            }
            case Command.Resume c -> {
                BuildingView bd = find(c.buildingId());
                yield new Preview(null, DEFINITION_REVISION, Map.of(),
                        rateDelta(bd.kind(), bd.paused() ? 0 : bd.kind().outputRate(bd.stage()), bd.kind().outputRate(bd.stage())));
            }
            case Command.Move c -> new Preview(null, DEFINITION_REVISION, Map.of(), Map.of());
            case Command.Storage c -> new Preview(null, DEFINITION_REVISION, EconomyState.STORAGE_COST.get(c.resource()), Map.of());
            case Command.Expand c -> new Preview(null, DEFINITION_REVISION, EconomyState.EXPAND_COST, Map.of());
        };
    }

    /** Rechnet die Schritte bis now nach. Serialisiert mit Befehlen, damit kein Auftrag mitten im Schritt landet. */
    public synchronized List<StepReport> advanceTo(long now) {
        return state.advanceTo(now);
    }

    private boolean sameRealm(CommandRequest request) {
        return request.realmId().equals(state.realmId());
    }

    /** Prüfungen, die vor der Annahme feststehen. Kosten, Belegung und Bauplatz prüft der Schritt zur Ausführung. */
    private Rejection check(CommandRequest request) {
        if (request.definitionRevision() != DEFINITION_REVISION) return Rejection.STALE_DEFINITION;
        return switch (request.command()) {
            case Command.Upgrade c -> upgradeCheck(c.buildingId(), request.expectedStage());
            case Command.Pause c -> known(c.buildingId());
            case Command.Resume c -> known(c.buildingId());
            case Command.Move c -> known(c.buildingId());
            case Command.Storage c -> state.snapshot().storageDone().contains(c.resource()) ? Rejection.STORAGE_DONE : null;
            case Command.Expand c -> state.unlockedPlots().contains(Plot.E1) ? Rejection.ALREADY_UNLOCKED : null;
            default -> null;
        };
    }

    private Rejection upgradeCheck(int id, int expectedStage) {
        BuildingView bd = find(id);
        if (bd == null) return Rejection.UNKNOWN_BUILDING;
        if (bd.stage() >= bd.kind().stages()) return Rejection.MAX_STAGE;
        if (bd.pendingStage() > 0) return Rejection.STAGE;
        return expectedStage == bd.stage() + 1 ? null : Rejection.STALE_STAGE;
    }

    private Rejection known(int id) {
        return find(id) == null ? Rejection.UNKNOWN_BUILDING : null;
    }

    private BuildingView find(int id) {
        return state.buildings().stream().filter(b -> b.id() == id).findFirst().orElse(null);
    }

    /** Änderung der Nettoraten in Ausgangs- und Eingangsressource; das Sägewerk verbraucht Ausgang / Verhältnis. */
    private static Map<Resource, Long> rateDelta(BuildingKind kind, long before, long after) {
        Map<Resource, Long> delta = new EnumMap<>(Resource.class);
        long out = after - before;
        delta.put(kind.output(), out);
        if (kind.input() != null) delta.put(kind.input(), -out / kind.ratio());
        return delta;
    }
}
