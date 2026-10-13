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
 * Einziger Schreibweg für Befehle des Servers (Issue #6). Alle Aufrufe sind über die Sperre des Zustands serialisiert, auch über
 * mehrere Handler desselben Zustands. Kosten und Raten kommen aus den Definitionen des Kerns; Werte aus dem Client dienen nur als
 * Prüfgrößen.
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
     * Vorschau gegen den aktuellen Zustand. rejection ist null, wenn der Befehl jetzt ausführbar wäre. nominalRateDelta sind
     * Nennraten aus den Definitionen. currentRateDelta ist eine momentane Prognose: die Nettoratenänderung des ersten Schritts nach
     * dem Befehl, mit aktuellem Vorrat, Kosten und Lagergrenzen. Sie gilt nur für diesen Zustand; der Schrittbeginn prüft erneut.
     */
    public record Preview(Rejection rejection, long definitionRevision, Map<Resource, Long> cost,
            Map<Resource, Long> nominalRateDelta, Map<Resource, Long> currentRateDelta) {}

    private final EconomyState state;
    /** Nach dem Entladen der Sitzung abgeschaltet; alte Handler-Referenzen wirken dann nicht mehr. */
    private boolean revoked;

    public CommandHandler(EconomyState state) {
        this.state = state;
    }

    /**
     * Reiht einen Auftrag zur Systemzeit now ein. Zustandsregeln prüft erst der Schrittbeginn. Eine bekannte Request-Kennung
     * liefert ihren Stand, ohne den Auftrag erneut einzureihen.
     */
    public Result submit(CommandRequest request, long now) {
        synchronized (state) {
            requireActive();
            if (!sameRealm(request)) return new Result(RequestStatus.REJECTED, Rejection.WRONG_REALM, DEFINITION_REVISION);
            RequestRecord known = state.requestStatus(request.requestId());
            if (known != null) return new Result(known.status(), known.rejection(), DEFINITION_REVISION);
            if (request.definitionRevision() != DEFINITION_REVISION) {
                return new Result(RequestStatus.REJECTED, Rejection.STALE_DEFINITION, DEFINITION_REVISION);
            }
            // Die niedrigen 64 Bit der Kennung tragen die Sequenz. Angenommen wird nur die nächste Sequenz; ältere unbekannte
            // Kennungen sind verfallen, weil ihre Ergebnisse nicht mehr vorliegen (Protokoll, ADR 0002 Abschnitt 14).
            long seq = request.requestId().getLeastSignificantBits();
            if (seq < state.nextSequence()) return new Result(RequestStatus.REJECTED, Rejection.REQUEST_EXPIRED, DEFINITION_REVISION);
            if (seq != state.nextSequence()) return new Result(RequestStatus.REJECTED, Rejection.SEQUENCE_MISMATCH, DEFINITION_REVISION);
            state.command(now, request.requestId(), request.expectedStage(), request.command());
            return new Result(RequestStatus.QUEUED, null, DEFINITION_REVISION);
        }
    }

    /** Stand eines bereits eingereihten Auftrags; leer, wenn die Kennung unbekannt ist. */
    public Optional<Result> status(UUID requestId) {
        synchronized (state) {
            requireActive();
            return Optional.ofNullable(state.requestStatus(requestId))
                    .map(r -> new Result(r.status(), r.rejection(), DEFINITION_REVISION));
        }
    }

    /** Vorschau mit denselben Prüfungen und Definitionen wie die Ausführung. Sie reiht nichts ein. */
    public Preview preview(CommandRequest request) {
        synchronized (state) {
            requireActive();
            return previewLocked(request);
        }
    }

    /** Rechnet die Schritte bis now nach. Serialisiert mit Aufträgen, damit kein Befehl mitten im Schritt landet. */
    public List<StepReport> advanceTo(long now) {
        synchronized (state) {
            requireActive();
            return state.advanceTo(now);
        }
    }

    /** Schaltet diesen Handler ab. Nach dem Aufruf wirft jede Operation, bis die Sitzung eine neue Wirtschaft lädt. */
    void revoke() {
        synchronized (state) {
            revoked = true;
        }
    }

    private void requireActive() {
        if (revoked) throw new IllegalStateException("Wirtschaft ist entladen");
    }

    private Preview previewLocked(CommandRequest request) {
        EconomyState.validate(request.command());
        if (!sameRealm(request)) return new Preview(Rejection.WRONG_REALM, DEFINITION_REVISION, Map.of(), Map.of(), Map.of());
        if (request.definitionRevision() != DEFINITION_REVISION) {
            return new Preview(Rejection.STALE_DEFINITION, DEFINITION_REVISION, Map.of(), Map.of(), Map.of());
        }
        Rejection rejection = state.check(request.command(), request.expectedStage());
        if (rejection == Rejection.UNKNOWN_BUILDING) {
            return new Preview(rejection, DEFINITION_REVISION, Map.of(), Map.of(), Map.of());
        }
        // Kosten und Nennraten stehen auch bei fachlicher Ablehnung im Ergebnis; die Ablehnung nennt den Grund.
        // Die momentane Prognose gilt nur für einen ausführbaren Befehl.
        Map<Resource, Long> current = rejection == null ? state.currentRateDelta(request.command()) : Map.of();
        return new Preview(rejection, DEFINITION_REVISION, cost(request.command()), nominalRateDelta(request.command()), current);
    }

    private boolean sameRealm(CommandRequest request) {
        return request.realmId().equals(state.realmId());
    }

    private BuildingView find(int id) {
        return state.buildings().stream().filter(b -> b.id() == id).findFirst().orElseThrow();
    }

    private Map<Resource, Long> cost(Command command) {
        return state.costOf(command);
    }

    private Map<Resource, Long> nominalRateDelta(Command command) {
        return switch (command) {
            case Command.Build c -> rateDelta(c.kind(), 0, c.kind().outputRate(1));
            case Command.Upgrade c -> {
                BuildingView bd = find(c.buildingId());
                // Auf der Maximalstufe gibt es keine nächste Rate; die Ablehnung MAX_STAGE trägt den Grund.
                if (bd.stage() >= bd.kind().stages()) yield Map.of();
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
