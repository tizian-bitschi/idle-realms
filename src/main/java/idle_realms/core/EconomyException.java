package idle_realms.core;

/** Strukturierter Fehler für ungültige Definitionen und Mengen. Der Zustand bleibt dabei unverändert. */
public final class EconomyException extends RuntimeException {
    public enum Reason {
        INVALID_AMOUNT,
        INVALID_BUILDING,
        STOCK_ABOVE_CAPACITY
    }

    private final Reason reason;

    EconomyException(Reason reason, String detail) {
        super(reason + ": " + detail);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
