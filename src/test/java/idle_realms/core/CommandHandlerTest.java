package idle_realms.core;

import static idle_realms.core.BuildingKind.FOREST;
import static idle_realms.core.BuildingKind.SAWMILL;
import static idle_realms.core.EconomyState.UNIT;
import static idle_realms.core.Plot.P1;
import static idle_realms.core.Plot.P2;
import static idle_realms.core.Plot.P3;
import static idle_realms.core.Plot.P4;
import static idle_realms.core.Resource.BOARDS;
import static idle_realms.core.Resource.WOOD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import idle_realms.core.CommandHandler.Outcome;
import idle_realms.core.EconomyState.Rejection;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Befehlsweg aus Issue #6: Request-Kennungen, Preisrevision, Zielstufe, Realm, Vorschau und Atomarität. */
class CommandHandlerTest {
    private static final UUID REALM = new UUID(1, 2);
    private static final long REV = CommandHandler.DEFINITION_REVISION;

    @Test
    void exactStockAcceptsPurchaseAndLeavesNothingBehind() {
        EconomyState st = state(Map.of(WOOD, 40 * UNIT));
        CommandHandler h = new CommandHandler(st);
        assertEquals(Outcome.ACCEPTED, h.submit(request(1, new Command.Build(FOREST, P3)), 0).outcome());
        assertNull(h.advanceTo(1_000).get(0).commands().get(0).rejection());
        assertEquals(0, st.stock(WOOD));
        assertEquals(1, st.buildings().size());
    }

    @Test
    void oneSubunitShortRejectsWholePurchaseWithoutChange() {
        EconomyState st = state(Map.of(WOOD, 40 * UNIT - 1));
        CommandHandler h = new CommandHandler(st);
        h.submit(request(1, new Command.Build(FOREST, P3)), 0);
        StepReport r = h.advanceTo(1_000).get(0);
        assertEquals(Rejection.COST, r.commands().get(0).rejection());
        assertEquals(40 * UNIT - 1, st.stock(WOOD));
        assertTrue(st.buildings().isEmpty());
        assertEquals(EconomyState.CAPACITY_START, st.capacity(WOOD));
    }

    @Test
    void duplicateDeliveryAppliesPurchaseOnce() {
        EconomyState st = state(Map.of(WOOD, 100 * UNIT));
        CommandHandler h = new CommandHandler(st);
        CommandRequest buy = request(1, new Command.Build(FOREST, P3));
        assertEquals(Outcome.ACCEPTED, h.submit(buy, 0).outcome());
        assertEquals(Outcome.DUPLICATE, h.submit(buy, 0).outcome());
        h.advanceTo(1_000);
        assertEquals(60 * UNIT, st.stock(WOOD));
        assertEquals(1, st.buildings().size());
    }

    @Test
    void twoOrdersAgainstSameStockNeverGoNegative() {
        EconomyState st = state(Map.of(WOOD, 40 * UNIT));
        CommandHandler h = new CommandHandler(st);
        assertEquals(Outcome.ACCEPTED, h.submit(request(1, new Command.Build(FOREST, P3)), 0).outcome());
        assertEquals(Outcome.ACCEPTED, h.submit(request(2, new Command.Build(FOREST, P4)), 0).outcome());
        List<StepReport.Applied> applied = h.advanceTo(1_000).get(0).commands();
        assertNull(applied.get(0).rejection());
        assertEquals(Rejection.COST, applied.get(1).rejection());
        assertEquals(0, st.stock(WOOD));
        assertEquals(1, st.buildings().size());
    }

    @Test
    void staleDefinitionRevisionIsRejectedWithCurrentRevision() {
        EconomyState st = state(Map.of(WOOD, 100 * UNIT));
        CommandHandler h = new CommandHandler(st);
        CommandHandler.Result result = h.submit(request(1, REV - 1, 0, new Command.Build(FOREST, P3)), 0);
        assertEquals(Outcome.REJECTED, result.outcome());
        assertEquals(Rejection.STALE_DEFINITION, result.rejection());
        assertEquals(REV, result.definitionRevision());
        assertTrue(h.advanceTo(1_000).get(0).commands().isEmpty());
        assertEquals(100 * UNIT, st.stock(WOOD));
    }

    @Test
    void foreignRealmIsRejected() {
        CommandHandler h = new CommandHandler(state(Map.of(WOOD, 100 * UNIT)));
        CommandRequest foreign = new CommandRequest(new UUID(0, 1), new UUID(9, 9), REV, 0, new Command.Build(FOREST, P3));
        assertEquals(Rejection.WRONG_REALM, h.submit(foreign, 0).rejection());
    }

    @Test
    void upgradeRejectsStaleTargetStageAndMaximumStage() {
        CommandHandler stageOne = new CommandHandler(state(Map.of(WOOD, 100 * UNIT), new Building(1, FOREST, P1, 1, false, 0)));
        assertEquals(Rejection.STALE_STAGE, stageOne.submit(upgrade(1, 3), 0).rejection());
        assertEquals(Outcome.ACCEPTED, stageOne.submit(upgrade(2, 2), 0).outcome());

        CommandHandler stageTwo = new CommandHandler(state(Map.of(WOOD, 100 * UNIT), new Building(1, FOREST, P1, 2, false, 0)));
        assertEquals(Rejection.MAX_STAGE, stageTwo.submit(upgrade(1, 3), 0).rejection());
    }

    @Test
    void unknownBuildingIsRejectedWithItsOwnCode() {
        CommandHandler h = new CommandHandler(state(Map.of()));
        assertEquals(Rejection.UNKNOWN_BUILDING, h.submit(request(1, new Command.Pause(99)), 0).rejection());
    }

    @Test
    void repeatedPauseIsIdempotentAndDoesNotChangeStock() {
        EconomyState st = state(Map.of(WOOD, 10 * UNIT), new Building(1, SAWMILL, P2, 1, false, 0));
        CommandHandler h = new CommandHandler(st);
        CommandRequest pause = request(1, new Command.Pause(1));
        assertEquals(Outcome.ACCEPTED, h.submit(pause, 0).outcome());
        assertEquals(Outcome.DUPLICATE, h.submit(pause, 0).outcome());
        assertEquals(Outcome.ACCEPTED, h.submit(request(2, new Command.Pause(1)), 0).outcome());
        StepReport r = h.advanceTo(1_000).get(0);
        assertEquals(Arrays.asList(null, null), r.commands().stream().map(StepReport.Applied::rejection).toList());
        assertEquals(10 * UNIT, st.stock(WOOD));
        assertEquals(1, st.buildings().size());
    }

    @Test
    void moveKeepsLevelPauseStateAndStockWithoutCost() {
        EconomyState st = state(Map.of(WOOD, 5 * UNIT), new Building(1, FOREST, P1, 2, false, 0));
        CommandHandler h = new CommandHandler(st);
        h.submit(request(1, new Command.Move(1, P3)), 0);
        StepReport r = h.advanceTo(1_000).get(0);
        assertNull(r.commands().get(0).rejection());
        assertEquals(5 * UNIT, r.stock().get(WOOD));
        EconomyState.BuildingView moved = st.buildings().get(0);
        assertEquals(P3, moved.plot());
        assertEquals(2, moved.stage());
        assertFalse(moved.paused());
    }

    @Test
    void expansionIsPaidOnceEvenIfTwoOrdersAreQueuedBeforeExecution() {
        EconomyState st = state(Map.of(WOOD, 240 * UNIT, BOARDS, 80 * UNIT));
        CommandHandler h = new CommandHandler(st);
        assertEquals(Outcome.ACCEPTED, h.submit(request(1, new Command.Expand()), 0).outcome());
        assertEquals(Outcome.ACCEPTED, h.submit(request(2, new Command.Expand()), 0).outcome());
        List<StepReport.Applied> applied = h.advanceTo(1_000).get(0).commands();
        assertNull(applied.get(0).rejection());
        assertEquals(Rejection.ALREADY_UNLOCKED, applied.get(1).rejection());
        assertEquals(120 * UNIT, st.stock(WOOD));
        assertEquals(40 * UNIT, st.stock(BOARDS));
        assertEquals(Rejection.ALREADY_UNLOCKED, h.submit(request(3, new Command.Expand()), 1_000).rejection());
    }

    @Test
    void previewShowsCostAndRateChangesOfTheSameDefinitions() {
        EconomyState st = state(Map.of(WOOD, 100 * UNIT));
        CommandHandler h = new CommandHandler(st);
        CommandHandler.Preview p = h.preview(request(1, new Command.Build(SAWMILL, P2)));
        assertNull(p.rejection());
        assertEquals(Map.of(WOOD, 60 * UNIT), p.cost());
        assertEquals(Map.of(BOARDS, 4_000L, WOOD, -1_000L), p.rateDelta());
        assertTrue(h.advanceTo(1_000).get(0).commands().isEmpty());
    }

    @Test
    void stalePreviewGivesNoPriceAndNamesCurrentRevision() {
        CommandHandler h = new CommandHandler(state(Map.of(WOOD, 100 * UNIT)));
        CommandHandler.Preview p = h.preview(request(1, REV - 1, 0, new Command.Build(FOREST, P3)));
        assertEquals(Rejection.STALE_DEFINITION, p.rejection());
        assertTrue(p.cost().isEmpty());
        assertEquals(REV, p.definitionRevision());
    }

    @Test
    void requestIdsSurviveSnapshotAndPreventRedeliveryAfterRestore() {
        EconomyState st = state(Map.of(WOOD, 100 * UNIT));
        CommandRequest buy = request(1, new Command.Build(FOREST, P3));
        new CommandHandler(st).submit(buy, 0);
        CommandHandler restored = new CommandHandler(EconomyState.restore(st.snapshot()));
        assertEquals(Outcome.DUPLICATE, restored.submit(buy, 0).outcome());
    }

    private static EconomyState state(Map<Resource, Long> stock, Building... buildings) {
        return new EconomyState(REALM, 0, stock, Map.of(), List.of(buildings));
    }

    private static CommandRequest request(long id, Command command) {
        return request(id, REV, 0, command);
    }

    private static CommandRequest request(long id, long revision, int expectedStage, Command command) {
        return new CommandRequest(new UUID(0, id), REALM, revision, expectedStage, command);
    }

    private static CommandRequest upgrade(long id, int expectedStage) {
        return request(id, REV, expectedStage, new Command.Upgrade(1));
    }
}
