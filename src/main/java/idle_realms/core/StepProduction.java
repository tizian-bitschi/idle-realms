package idle_realms.core;

import idle_realms.core.StepReport.Row;
import idle_realms.core.StepReport.Status;
import java.util.EnumMap;

/**
 * Produktion eines Schritts (ADR 0002, Abschnitt 5.1). Eingangspool und freier Ausgang gelten zu Schrittbeginn; jeder Betrieb
 * in ID-Reihenfolge verringert sie für die folgenden. Die Vorratsänderung entsteht erst in {@link #delta(Resource)}.
 */
final class StepProduction {
    private final EnumMap<Resource, Long> avail;
    private final EnumMap<Resource, Long> free = new EnumMap<>(Resource.class);
    private final EnumMap<Resource, Long> delta = new EnumMap<>(Resource.class);

    StepProduction(EnumMap<Resource, Long> stock, EnumMap<Resource, Long> capacity) {
        avail = new EnumMap<>(stock);
        for (Resource r : Resource.values()) {
            free.put(r, capacity.get(r) - stock.get(r));
            delta.put(r, 0L);
        }
    }

    /** Berechnet einen Betrieb aus den Restwerten der vorherigen Betriebe und verbucht seine Entnahme und Ausgabe. */
    Row produce(Building bd, long b) {
        if (bd.paused) return new Row(bd.id, Status.PAUSED, false, 0, 0);
        if (b < bd.activeFrom) return new Row(bd.id, Status.RUNNING, true, 0, 0);
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
        return new Row(bd.id, status, false, produced, consumed);
    }

    long delta(Resource resource) {
        return delta.get(resource);
    }
}
