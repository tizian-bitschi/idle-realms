package idle_realms.core;

import static idle_realms.core.BuildingKind.FOREST;
import static idle_realms.core.BuildingKind.SAWMILL;
import static idle_realms.core.EconomyState.UNIT;
import static idle_realms.core.Plot.E1;
import static idle_realms.core.Plot.P1;
import static idle_realms.core.Plot.P2;
import static idle_realms.core.Plot.P3;
import static idle_realms.core.Plot.P4;
import static idle_realms.core.Resource.BOARDS;
import static idle_realms.core.Resource.WOOD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import idle_realms.core.EconomyState.BuildingView;
import idle_realms.core.EconomyState.PendingCommand;
import idle_realms.core.EconomyState.Rejection;
import idle_realms.core.EconomyState.RequestRecord;
import idle_realms.core.EconomyState.RequestStatus;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Textform des Wirtschaftsstands aus Issue #7: vollständige Felder, Schemaversion und strenge Ablehnung. */
class EconomySnapshotTextTest {
    private static final UUID REALM = new UUID(1, 2);
    private static final PlotZone FREE = plot -> true;

    @Test
    void everyFieldSurvivesRoundTrip() {
        EconomySnapshot s = handCraftedSnapshot();

        String text = EconomySnapshotText.encode(s);

        EconomySnapshot decoded = EconomySnapshotText.decode(text);
        assertEquals(s, decoded);
        assertEquals(text, EconomySnapshotText.encode(decoded));
    }

    @Test
    void stateAfterCommandsAndPartialStepRoundTrips() {
        EconomyState state = EconomyState.start(REALM, 250, FREE);
        CommandHandler handler = new CommandHandler(state);
        handler.submit(request(1, new Command.Build(FOREST, P3)), 1_000);
        handler.submit(request(2, new Command.Storage(WOOD)), 1_500);
        handler.advanceTo(4_750);
        handler.submit(request(3, new Command.Pause(1)), 5_300);

        EconomySnapshot s = state.snapshot();

        assertFalse(s.pending().isEmpty());
        assertEquals(s, EconomySnapshotText.decode(EconomySnapshotText.encode(s)));
    }

    @Test
    void snapshotBeyondTheOldStringLimitRoundTrips() {
        EconomyState state = EconomyState.start(REALM, 0, FREE);
        CommandHandler handler = new CommandHandler(state);
        for (long id = 1; id <= 1_600; id++) handler.submit(request(id, new Command.Storage(WOOD)), 0);
        EconomySnapshot s = state.snapshot();

        String text = EconomySnapshotText.encode(s);

        assertTrue(text.getBytes(StandardCharsets.UTF_8).length > 65_535);
        assertEquals(s, EconomySnapshotText.decode(text));
    }

    @Test
    void truncatedOrEditedHistoryIsRejected() {
        String[] lines = EconomySnapshotText.encode(handCraftedSnapshot()).split("\n");
        for (int cut = 1; cut < lines.length; cut++) {
            assertInvalid(String.join("\n", java.util.Arrays.copyOfRange(lines, 0, cut)) + "\n");
        }
        for (int drop = 1; drop < lines.length - 1; drop++) {
            List<String> kept = new java.util.ArrayList<>(List.of(lines));
            kept.remove(drop);
            assertInvalid(String.join("\n", kept) + "\n");
        }
    }

    @Test
    void unknownSchemaVersionIsRejected() {
        String text = EconomySnapshotText.encode(handCraftedSnapshot()).replace("idle-realms-economy 1", "idle-realms-economy 2");

        assertInvalid(text);
    }

    @Test
    void corruptTextIsRejected() {
        String valid = EconomySnapshotText.encode(handCraftedSnapshot());
        List<String> corrupt = List.of(
                "",
                "garbage\n" + valid.substring(valid.indexOf('\n') + 1),
                valid.replace("epoch 250", "epoch zwei"),
                valid.replace("stock WOOD ", "stock WOOD 1 2 3 "),
                valid.replace("realm ", "realm x"),
                valid + "plot P1\n",
                valid + "mystery 1\n",
                valid.replace("request ", "request not-a-uuid "),
                valid.replace("build SAWMILL P2", "build SAWMILL P9"),
                valid.replace("building 1 FOREST P1", "building 1 FOREST P1 extra"),
                valid.substring(0, valid.length() - 8));
        for (String text : corrupt) assertInvalid(text);
    }

    private static void assertInvalid(String text) {
        EconomyException e = assertThrows(EconomyException.class, () -> EconomySnapshotText.decode(text));
        assertEquals(EconomyException.Reason.INVALID_SNAPSHOT, e.reason());
    }

    /** Ein Zustand mit allen Befehlsarten, Anzeigeresten, Aufträgen aller Endstände und dem erschlossenen Bauplatz E1. */
    private static EconomySnapshot handCraftedSnapshot() {
        UUID a = new UUID(0, 1);
        UUID b = new UUID(0, 2);
        UUID c = new UUID(0, 3);
        List<PendingCommand> pending = List.of(
                new PendingCommand(1_000, 1, new Command.Build(SAWMILL, P2), a, 0),
                new PendingCommand(1_000, 2, new Command.Upgrade(1), b, 2),
                new PendingCommand(2_000, 3, new Command.Pause(1), null, 0),
                new PendingCommand(2_000, 4, new Command.Resume(1), null, 0),
                new PendingCommand(2_000, 5, new Command.Move(1, P4), c, 0),
                new PendingCommand(3_000, 6, new Command.Storage(BOARDS), null, 0),
                new PendingCommand(3_000, 7, new Command.Expand(), null, 0));
        Map<Resource, Long> stock = Map.of(WOOD, 1_234_567L, BOARDS, 42 * UNIT);
        Map<Resource, Long> capacity = Map.of(WOOD, 2_400 * UNIT, BOARDS, 1_200 * UNIT);
        BuildingView forest = new BuildingView(1, FOREST, P1, 2, false, 2, 500, 250,
                new StepReport.Row(1, StepReport.Status.RUNNING, false, 3_000, 0));
        BuildingView sawmill = new BuildingView(2, SAWMILL, P2, 1, true, 1, 0, 0, null);
        Map<UUID, RequestRecord> requests = Map.of(
                a, new RequestRecord(RequestStatus.APPLIED, null),
                b, new RequestRecord(RequestStatus.REJECTED, Rejection.COST),
                c, new RequestRecord(RequestStatus.QUEUED, null));
        return new EconomySnapshot(REALM, 250, 4_250, 9, stock, capacity, Set.of(WOOD), Set.of(P1, P2, P3, P4, E1),
                List.of(forest, sawmill), pending, 8, 3, requests);
    }

    private static CommandRequest request(long id, Command command) {
        return new CommandRequest(new UUID(0, id), REALM, CommandHandler.DEFINITION_REVISION, 0, command);
    }
}
