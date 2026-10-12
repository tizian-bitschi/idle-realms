package idle_realms.core;

import java.util.Map;

/**
 * Betriebstypen mit Raten je Stufe in Untereinheiten je Schritt (ADR 0002, Abschnitte 2 und 3). Alle Zahlen sind Testwerte.
 * Ein Sägewerk verarbeitet Eingang und erzeugt Ausgang im festen Verhältnis {@code ratio}; der Forstbetrieb hat {@code ratio} 1.
 */
public enum BuildingKind {
    FOREST(null, Resource.WOOD, 1, new long[] {2_000, 3_000}, 3,
            Map.of(Resource.WOOD, 40 * EconomyState.UNIT), Map.of(Resource.WOOD, 60 * EconomyState.UNIT)),
    SAWMILL(Resource.WOOD, Resource.BOARDS, 4, new long[] {4_000, 8_000}, 2,
            Map.of(Resource.WOOD, 60 * EconomyState.UNIT),
            Map.of(Resource.WOOD, 80 * EconomyState.UNIT, Resource.BOARDS, 20 * EconomyState.UNIT));

    private final Resource input;
    private final Resource output;
    private final int ratio;
    private final long[] outputRates;
    private final int maxCount;
    private final Map<Resource, Long> buildCost;
    private final Map<Resource, Long> upgradeCost;

    BuildingKind(Resource input, Resource output, int ratio, long[] outputRates, int maxCount,
            Map<Resource, Long> buildCost, Map<Resource, Long> upgradeCost) {
        this.input = input;
        this.output = output;
        this.ratio = ratio;
        this.outputRates = outputRates;
        this.maxCount = maxCount;
        this.buildCost = buildCost;
        this.upgradeCost = upgradeCost;
    }

    /** Eingangsressource; null bei Betrieben ohne Eingang. */
    public Resource input() {
        return input;
    }

    public Resource output() {
        return output;
    }

    /** Ausgang je Eingang: 1 bei Forstbetrieben, 4 bei Sägewerken. */
    public int ratio() {
        return ratio;
    }

    /** Anzahl der Stufen; Stufe 1 ist der Bau. */
    public int stages() {
        return outputRates.length;
    }

    /** Nennausgang je Schritt in der angegebenen Stufe (1-basiert). */
    public long outputRate(int stage) {
        return outputRates[stage - 1];
    }

    public int maxCount() {
        return maxCount;
    }

    public Map<Resource, Long> buildCost() {
        return buildCost;
    }

    public Map<Resource, Long> upgradeCost() {
        return upgradeCost;
    }
}
