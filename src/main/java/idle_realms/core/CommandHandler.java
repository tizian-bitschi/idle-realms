package idle_realms.core;

import idle_realms.core.EconomyState.BuildingView;
import idle_realms.core.EconomyState.RequestRecord;
import idle_realms.core.EconomyState.RequestStatus;
import idle_realms.core.EconomyState.Rejection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Einziger Schreibweg für Befehle des Servers (Issue #6). Alle Aufrufe sind über die Sperre des Zustands serialisiert, auch über mehrere Handler desselben Zustands. Kosten und
 * Raten kommen aus den Definitionen des Kerns; Werte aus dem Client dienen nur als Prüfgrößen.
 */
public final class CommandHandler {
    /** Revision der preisrelevanten Definitionen. Jede Änderung an Kosten oder Raten erhöht sie. */
    public static final long DEFINITION_REVISION = 1;

    /**
     * Stand eines Auftrags. QUEUED nach der Annahme; danach der Endstand am Schrittbeginn. rejection ist nur bei REJECTED
     * gesetzt; definitionRevision nennt den aktuellen Stand der Definitionen.
     */
    public record Result(RequestStatus status, Rejection rejection, long definitionRevision) {}

    /**
     * Vorschau gegen den aktuellen Zustand. rejection ist null, wenn der Befehl jetzt ausführbar wäre. rateDelta sind Nennraten;
     * die tatsächliche Produktion hängt vom Vorrat am Schrittbeginn ab und wird dort erneut geprüft.
     */
    public record Preview(Rejection rejection, long definitionRevision, Map<Resource, Long> cost, Map<Resource, Long> rateDelta) {}

    private final EconomyState state;

    public CommandHandler(EconomyState state) {
        this.state = state;
    }

    /**
     * Reiht einen Auftrag zur Systemzeit now ein. Zustandsregeln prüft erst der Schrittbeginn. Eine bekannte Request-Kennung
     * liefert ihren Stand, ohne den Auftrag erneut einzureihen.
     */
    public Result submit(CommandRequest request, long now) {
        synchronized (state) {
            return submitLocked(request, now);
        }
    }

    private Result submitLocked(CommandRequest request, long now) {
        if (!sameRealm(request)) return new Result(RequestStatus.REJECTED, Rejection.WRONG_REALM, DEFINITION_REVISION);
        RequestRecord known = state.requestStatus(request.requestId());
        if (known != null) return new Result(known.status(), known.rejection(), DEFINITION_REVISION);
        if (request.definitionRevision() != DEFINITION_REVISION) {
            return new Result(RequestStatus.REJECTED, Rejection.STALE_DEFINITION, DEFINITION_REVISION);
        }
        state.command(now, request.requestId(), request.expectedStage(), request.command());
        return new Result(RequestStatus.QUEUED, null, DEFINITION_REVISION);
    }

    /** Stand eines bereits eingereihten Auftrags; leer, wenn die Kennung unbekannt ist. */
    public Optional<Result> status(UUID requestId) {
        synchronized (state) {
            return Optional.ofNullable(state.requestStatus(requestId))
                    .map(r -> new Result(r.status(), r.rejection(), DEFINITION_REVISION));
        }
    }

    /** Vorschau mit denselben Prüfungen und Definitionen wie die Ausführung. Sie reiht nichts ein. */
    public Preview preview(CommandRequest request) {
        synchronized (state) {
            return previewLocked(request);
        }
    }

    private Preview previewLocked(CommandRequest request) {
        EconomyState.validate(request.command());
        if (!sameRealm(request)) return new Preview(Rejection.WRONG_REALM, DEFINITION_REVISION, Map.of(), Map.of());
        if (request.definitionRevision() != DEFINITION_REVISION) {
            return new Preview(Rejection.STALE_DEFINITION, DEFINITION_REVISION, Map.of(), Map.of());
        }
        Rejection rejection = state.check(request.command(), request.expectedStage());
        if (rejection != null) return new Preview(rejection, DEFINITION_REVISION, Map.of(), Map.of());
        return new Preview(null, DEFINITION_REVISION, cost(request.command()), nominalRateDelta(request.command()));
    }

    /** Rechnet die Schritte bis now nach. Serialisiert mit Aufträgen, damit kein Befehl mitten im Schritt landet. */
    public List<StepReport> advanceTo(long now) {
        synchronized (state) {
            return state.advanceTo(now);
        }
    }

    private boolean sameRealm(CommandRequest request) {
        return request.realmId().equals(state.realmId());
    }

    private BuildingView find(int id) {
        return state.buildings().stream().filter(b -> b.id() == id).findFirst().orElseThrow();
    }

    private Map<Resource, Long> cost(Command command) {
        return switch (command) {
            case Command.Build c -> c.kind().buildCost();
            case Command.Upgrade c -> find(c.buildingId()).kind().upgradeCost();
            case Command.Storage c -> EconomyState.STORAGE_COST.get(c.resource());
            case Command.Expand c -> EconomyState.EXPAND_COST;
            default -> Map.of();
        };
    }

    private Map<Resource, Long> nominalRateDelta(Command command) {
        return switch (command) {
            case Command.Build c -> rateDelta(c.kind(), 0, c.kind().outputRate(1));
            case Command.Upgrade c -> {
                BuildingView bd = find(c.buildingId());
                yield rateDelta(bd.kind(), nominal(bd, bd.stage()), nominal(bd, bd.stage() + 1));
            }
            case Command.Pause c -> {
                BuildingView bd = find(c.buildingId());
                yield rateDelta(bd.kind(), nominal(bd, bd.stage()), 0);
            }
            case Command.Resume c -> {
                BuildingView bd = find(c.buildingId());
                yield rateDelta(bd.kind(), nominal(bd, bd.stage()), bd.kind().outputRate(bd.stage()));
            }
            default -> Map.of();
        };
    }

    /** Nennausgang eines Betriebs in der angegebenen Stufe; pausierte Betriebe liefern 0. */
    private static long nominal(BuildingView bd, int stage) {
        return bd.paused() ? 0 : bd.kind().outputRate(stage);
    }

    /** Änderung der Nettoraten: Ausgang steigt um out, das Sägewerk verbraucht out / Verhältnis mehr Eingang. */
    private static Map<Resource, Long> rateDelta(BuildingKind kind, long before, long after) {
        Map<Resource, Long> delta = new EnumMap<>(Resource.class);
        long out = after - before;
        if (out == 0) return delta;
        delta.put(kind.output(), out);
        if (kind.input() != null) delta.put(kind.input(), -out / kind.ratio());
        return delta;
    }
}
