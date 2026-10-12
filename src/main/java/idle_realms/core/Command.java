package idle_realms.core;

/** Befehle des Spielers. Sie wirken am Schrittbeginn, den {@link EconomyState#command} bestimmt. */
public sealed interface Command {
    record Build(BuildingKind kind, Plot plot) implements Command {}

    record Upgrade(int buildingId) implements Command {}

    record Pause(int buildingId) implements Command {}

    record Resume(int buildingId) implements Command {}

    record Move(int buildingId, Plot plot) implements Command {}

    record Storage(Resource resource) implements Command {}

    record Expand() implements Command {}
}
