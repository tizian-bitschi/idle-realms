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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import idle_realms.core.CommandHandler.Result;
import idle_realms.core.EconomyState.RequestStatus;
import idle_realms.core.EconomyState.Rejection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Befehlsweg aus Issue #6: Request-Kennungen, Preisrevision, Zielstufe, Realm, Zone, Vorschau und Nebenläufigkeit. */
class CommandHandlerTest {
    private static final UUID REALM = new UUID(1, 2);
    private static final long REV = CommandHandler.DEFINITION_REVISION;
    private static final PlotZone OPEN = plot -> true;

    @Test
    void exactStockAcceptsPurchaseAndLeavesNothingBehind() {
        EconomyState st = state(Map.of(WOOD, 40 * UNIT));
        CommandHandler h = new CommandHandler(st);
        assertEquals(RequestStatus.QUEUED, h.submit(request(1, new Command.Build(FOREST, P3)), 0).status());
        h.advanceTo(1_000);
        assertEquals(RequestStatus.APPLIED, status(h, 1).status());
        assertEquals(0, st.stock(WOOD));
        assertEquals(1, st.buildings().size());
    }

    @Test
    void oneSubunitShortRejectsWholePurchaseWithoutChange() {
        EconomyState st = state(Map.of(WOOD, 40 * UNIT - 1));
        CommandHandler h = new CommandHandler(st);
        h.submit(request(1, new Command.Build(FOREST, P3)), 0);
        h.advanceTo(1_000);
        assertEquals(RequestStatus.REJECTED, status(h, 1).status());
        assertEquals(Rejection.COST, status(h, 1).rejection());
        assertEquals(40 * UNIT - 1, st.stock(WOOD));
        assertTrue(st.buildings().isEmpty());
        assertEquals(EconomyState.CAPACITY_START, st.capacity(WOOD));
    }

    @Test
    void duplicateDeliveryReturnsStoredResultAndAppliesOnce() {
        EconomyState st = state(Map.of(WOOD, 100 * UNIT));
        CommandHandler h = new CommandHandler(st);
        CommandRequest buy = request(1, new Command.Build(FOREST, P3));
        assertEquals(RequestStatus.QUEUED, h.submit(buy, 0).status());
        assertEquals(RequestStatus.QUEUED, h.submit(buy, 0).status());
        h.advanceTo(1_000);
        assertEquals(RequestStatus.APPLIED, h.submit(buy, 1_000).status());
        assertEquals(60 * UNIT, st.stock(WOOD));
        assertEquals(1, st.buildings().size());
    }

    @Test
    void twoOrdersAgainstSameStockNeverGoNegative() {
        EconomyState st = state(Map.of(WOOD, 40 * UNIT));
        CommandHandler h = new CommandHandler(st);
        h.submit(request(1, new Command.Build(FOREST, P3)), 0);
        h.submit(request(2, new Command.Build(FOREST, P4)), 0);
        h.advanceTo(1_000);
        assertEquals(RequestStatus.APPLIED, status(h, 1).status());
        assertEquals(Rejection.COST, status(h, 2).rejection());
        assertEquals(0, st.stock(WOOD));
        assertEquals(1, st.buildings().size());
    }

    @Test
    void concurrentSubmissionsForOnePlotApplyExactlyOnce() {
        EconomyState st = state(Map.of(WOOD, 100 * UNIT));
        CommandHandler h = new CommandHandler(st);
        IntStream.rangeClosed(1, 40).parallel().forEach(i -> h.submit(request(i, new Command.Build(FOREST, P3)), 0));
        h.advanceTo(1_000);
        long applied = IntStream.rangeClosed(1, 40).filter(i -> status(h, i).status() == RequestStatus.APPLIED).count();
        long occupied = IntStream.rangeClosed(1, 40).filter(i -> status(h, i).rejection() == Rejection.PLOT_OCCUPIED).count();
        assertEquals(1, applied);
        assertEquals(39, occupied);
        assertEquals(60 * UNIT, st.stock(WOOD));
        assertEquals(1, st.buildings().size());
    }

    @Test
    void staleDefinitionAndForeignRealmAreRejectedAtSubmission() {
        CommandHandler h = new CommandHandler(state(Map.of(WOOD, 100 * UNIT)));
        Result stale = h.submit(request(1, REV - 1, 0, new Command.Build(FOREST, P3)), 0);
        assertEquals(Rejection.STALE_DEFINITION, stale.rejection());
        assertEquals(REV, stale.definitionRevision());
        assertTrue(h.status(new UUID(0, 1)).isEmpty());
        CommandRequest foreign = new CommandRequest(new UUID(0, 2), new UUID(9, 9), REV, 0, new Command.Build(FOREST, P3));
        assertEquals(Rejection.WRONG_REALM, h.submit(foreign, 0).rejection());
    }

    @Test
    void upgradeChecksExpectedTargetStageAtExecution() {
        CommandHandler stageOne = new CommandHandler(state(Map.of(WOOD, 100 * UNIT), new Building(1, FOREST, P1, 1, false, 0)));
        stageOne.submit(upgrade(1, 3), 0);
        stageOne.submit(upgrade(2, 2), 0);
        stageOne.advanceTo(1_000);
        assertEquals(Rejection.STALE_STAGE, status(stageOne, 1).rejection());
        assertEquals(RequestStatus.APPLIED, status(stageOne, 2).status());

        CommandHandler stageTwo = new CommandHandler(state(Map.of(WOOD, 100 * UNIT), new Building(1, FOREST, P1, 2, false, 0)));
        stageTwo.submit(upgrade(1, 3), 0);
        stageTwo.advanceTo(1_000);
        assertEquals(Rejection.MAX_STAGE, status(stageTwo, 1).rejection());
    }

    @Test
    void unknownBuildingIsRejectedAtExecutionWithItsOwnCode() {
        CommandHandler h = new CommandHandler(state(Map.of()));
        h.submit(request(1, new Command.Pause(99)), 0);
        h.advanceTo(1_000);
        assertEquals(Rejection.UNKNOWN_BUILDING, status(h, 1).rejection());
    }

    @Test
    void repeatedPauseIsIdempotentAndResumeOfRunningBuildingIsRejectedAsInAdr() {
        EconomyState st = state(Map.of(WOOD, 10 * UNIT), new Building(1, SAWMILL, P2, 1, false, 0));
        CommandHandler h = new CommandHandler(st);
        CommandRequest pause = request(1, new Command.Pause(1));
        h.submit(pause, 0);
        h.submit(request(2, new Command.Pause(1)), 0);
        h.advanceTo(1_000);
        assertEquals(RequestStatus.APPLIED, status(h, 1).status());
        assertEquals(RequestStatus.APPLIED, status(h, 2).status());
        assertEquals(10 * UNIT, st.stock(WOOD));
        h.submit(request(3, new Command.Resume(1)), 1_000);
        h.submit(request(4, new Command.Resume(1)), 1_000);
        h.advanceTo(2_000);
        assertEquals(RequestStatus.APPLIED, status(h, 3).status());
        assertEquals(Rejection.NOT_PAUSED, status(h, 4).rejection());
        assertEquals(RequestStatus.APPLIED, h.submit(pause, 2_000).status());
        assertEquals(1, st.buildings().size());
    }

    @Test
    void moveKeepsStageAndPauseStateWithoutCost() {
        EconomyState st = state(Map.of(WOOD, 5 * UNIT), new Building(1, FOREST, P1, 2, false, 0));
        CommandHandler h = new CommandHandler(st);
        h.submit(request(1, new Command.Move(1, P3)), 0);
        h.advanceTo(1_000);
        assertEquals(RequestStatus.APPLIED, status(h, 1).status());
        assertEquals(5 * UNIT, st.stock(WOOD));
        EconomyState.BuildingView moved = st.buildings().get(0);
        assertEquals(P3, moved.plot());
        assertEquals(2, moved.stage());
        assertFalse(moved.paused());
    }

    @Test
    void blockedZoneRejectsBuildAndMoveAtExecution() {
        PlotZone blockedP3 = plot -> plot != P3;
        EconomyState st = new EconomyState(REALM, 0, Map.of(WOOD, 100 * UNIT), Map.of(),
                List.of(new Building(1, FOREST, P1, 1, false, 0)), blockedP3);
        CommandHandler h = new CommandHandler(st);
        h.submit(request(1, new Command.Build(FOREST, P3)), 0);
        h.submit(request(2, new Command.Move(1, P3)), 0);
        h.advanceTo(1_000);
        assertEquals(Rejection.ZONE_BLOCKED, status(h, 1).rejection());
        assertEquals(Rejection.ZONE_BLOCKED, status(h, 2).rejection());
        assertEquals(100 * UNIT + 2_000, st.stock(WOOD));
        assertEquals(P1, st.buildings().get(0).plot());
    }

    @Test
    void expansionIsPaidOnceEvenIfTwoOrdersAreQueuedBeforeExecution() {
        EconomyState st = state(Map.of(WOOD, 240 * UNIT, BOARDS, 80 * UNIT));
        CommandHandler h = new CommandHandler(st);
        h.submit(request(1, new Command.Expand()), 0);
        h.submit(request(2, new Command.Expand()), 0);
        h.advanceTo(1_000);
        assertEquals(RequestStatus.APPLIED, status(h, 1).status());
        assertEquals(Rejection.ALREADY_UNLOCKED, status(h, 2).rejection());
        assertEquals(120 * UNIT, st.stock(WOOD));
        assertEquals(40 * UNIT, st.stock(BOARDS));
    }

    @Test
    void previewReportsTheRejectionExecutionWouldReportAndNominalRates() {
        CommandHandler occupied = new CommandHandler(state(Map.of(WOOD, 100 * UNIT), new Building(1, FOREST, P1, 1, false, 0)));
        assertEquals(Rejection.PLOT_OCCUPIED, occupied.preview(request(1, new Command.Build(FOREST, P1))).rejection());

        CommandHandler poor = new CommandHandler(state(Map.of(WOOD, 10 * UNIT)));
        CommandHandler.Preview unaffordable = poor.preview(request(2, new Command.Build(SAWMILL, P2)));
        assertEquals(Rejection.COST, unaffordable.rejection());
        assertEquals(Map.of(WOOD, 60 * UNIT), unaffordable.cost());

        CommandHandler rich = new CommandHandler(state(Map.of(WOOD, 100 * UNIT)));
        CommandHandler.Preview p = rich.preview(request(3, new Command.Build(SAWMILL, P2)));
        assertNull(p.rejection());
        assertEquals(Map.of(WOOD, 60 * UNIT), p.cost());
        assertEquals(Map.of(BOARDS, 4_000L, WOOD, -1_000L), p.nominalRateDelta());
    }

    @Test
    void previewOfUpgradeOfPausedBuildingShowsNoRateChange() {
        CommandHandler h = new CommandHandler(state(Map.of(WOOD, 100 * UNIT), new Building(1, FOREST, P1, 1, true, 0)));
        CommandHandler.Preview p = h.preview(upgrade(1, 2));
        assertNull(p.rejection());
        assertTrue(p.nominalRateDelta().isEmpty());
    }

    @Test
    void previewIsRejectedForStaleDefinitionAndQueuesNothing() {
        CommandHandler h = new CommandHandler(state(Map.of(WOOD, 100 * UNIT)));
        CommandHandler.Preview p = h.preview(request(1, REV - 1, 0, new Command.Build(FOREST, P3)));
        assertEquals(Rejection.STALE_DEFINITION, p.rejection());
        assertTrue(p.cost().isEmpty());
        assertEquals(REV, p.definitionRevision());
        assertTrue(h.advanceTo(1_000).get(0).commands().isEmpty());
    }

    @Test
    void requestStatusSurvivesSnapshotAndPreventsReplayAfterRestore() {
        EconomyState st = state(Map.of(WOOD, 100 * UNIT));
        CommandHandler h = new CommandHandler(st);
        CommandRequest buy = request(1, new Command.Build(FOREST, P3));
        h.submit(buy, 0);
        h.advanceTo(1_000);
        EconomyState restoredState = EconomyState.restore(st.snapshot(), OPEN);
        CommandHandler restored = new CommandHandler(restoredState);
        assertEquals(st.stock(WOOD), restoredState.stock(WOOD));
        assertEquals(RequestStatus.APPLIED, restored.submit(buy, 1_000).status());
        assertEquals(1, restoredState.buildings().size());
    }

    @Test
    void phaseSplitThroughHandlerMatchesWholeInterval() {
        Map<Resource, Long> stock = Map.of(WOOD, 500 * UNIT);
        EconomyState whole = state(stock, new Building(1, FOREST, P1, 1, false, 0));
        CommandHandler wholeHandler = new CommandHandler(whole);
        scheduleRf7(wholeHandler);
        List<StepReport> wholeReports = wholeHandler.advanceTo(6_000);

        EconomyState split = state(stock, new Building(1, FOREST, P1, 1, false, 0));
        CommandHandler splitHandler = new CommandHandler(split);
        scheduleRf7(splitHandler);
        List<StepReport> splitReports = new ArrayList<>(splitHandler.advanceTo(2_500));
        splitReports.addAll(splitHandler.advanceTo(4_500));
        splitReports.addAll(splitHandler.advanceTo(6_000));

        assertEquals(wholeReports, splitReports);
        assertEquals(406 * UNIT, wholeReports.stream().filter(r -> r.at() == 3_000).findFirst().orElseThrow().stock().get(WOOD));
    }

    @Test
    void twoHandlersOnOneStateShareTheLockAndApplyARepeatedIdOnce() {
        EconomyState st = state(Map.of(WOOD, 100 * UNIT));
        CommandHandler first = new CommandHandler(st);
        CommandHandler second = new CommandHandler(st);
        CommandRequest buy = request(1, new Command.Build(FOREST, P3));
        IntStream.range(0, 50).parallel().forEach(i -> (i % 2 == 0 ? first : second).submit(buy, 0));
        first.advanceTo(1_000);
        assertEquals(RequestStatus.APPLIED, status(second, 1).status());
        assertEquals(60 * UNIT, st.stock(WOOD));
        assertEquals(1, st.buildings().size());
    }

    @Test
    void adapterFailureLeavesStepUnchangedAndRetryApplies() {
        boolean[] broken = {false};
        PlotZone flaky = plot -> {
            if (broken[0] && plot == P4) throw new IllegalStateException("Welt nicht erreichbar");
            return true;
        };
        EconomyState st = new EconomyState(REALM, 0, Map.of(WOOD, 100 * UNIT), Map.of(), List.of(), flaky);
        CommandHandler h = new CommandHandler(st);
        h.submit(request(1, new Command.Build(FOREST, P3)), 0);
        h.submit(request(2, new Command.Build(FOREST, P4)), 0);
        long anchorBefore = st.anchor();
        broken[0] = true;
        assertThrows(IllegalStateException.class, () -> h.advanceTo(1_000));
        assertEquals(100 * UNIT, st.stock(WOOD));
        assertTrue(st.buildings().isEmpty());
        assertEquals(anchorBefore, st.anchor());
        assertEquals(RequestStatus.QUEUED, status(h, 1).status());
        assertEquals(RequestStatus.QUEUED, status(h, 2).status());
        broken[0] = false;
        h.advanceTo(1_000);
        assertEquals(RequestStatus.APPLIED, status(h, 1).status());
        assertEquals(RequestStatus.APPLIED, status(h, 2).status());
        assertEquals(20 * UNIT, st.stock(WOOD));
        assertEquals(2, st.buildings().size());
    }

    @Test
    void snapshotWithTwoQueuedCommandsForOneRequestIdIsRejected() {
        EconomyState st = state(Map.of(WOOD, 100 * UNIT));
        new CommandHandler(st).submit(request(1, new Command.Build(FOREST, P3)), 0);
        EconomySnapshot s = st.snapshot();
        EconomyState.PendingCommand first = s.pending().get(0);
        EconomyState.PendingCommand second = new EconomyState.PendingCommand(first.at(), first.seq() + 1,
                new Command.Build(FOREST, P4), first.requestId(), 0);
        EconomySnapshot doubled = new EconomySnapshot(s.realmId(), s.epoch(), s.anchor(), s.revision(), s.stock(), s.capacity(),
                s.storageDone(), s.unlockedPlots(), s.buildings(), List.of(first, second), s.nextSeq() + 1, s.nextId(), s.requests());
        EconomyException e = assertThrows(EconomyException.class, () -> EconomyState.restore(doubled, OPEN));
        assertEquals(EconomyException.Reason.INVALID_SNAPSHOT, e.reason());
    }

    @Test
    void previewOfUpgradeAtMaximumStageRejectsWithoutRateLookup() {
        CommandHandler h = new CommandHandler(state(Map.of(WOOD, 100 * UNIT), new Building(1, FOREST, P1, 2, false, 0)));
        CommandHandler.Preview p = h.preview(upgrade(1, 3));
        assertEquals(Rejection.MAX_STAGE, p.rejection());
        assertTrue(p.nominalRateDelta().isEmpty());
        assertTrue(p.currentRateDelta().isEmpty());
    }

    @Test
    void currentRateShowsWhatAStepProducesNowAndFollowsPause() {
        CommandHandler h = new CommandHandler(state(Map.of(WOOD, 0L), new Building(1, FOREST, P1, 1, false, 0)));
        CommandHandler.Preview paused = h.preview(request(1, new Command.Pause(1)));
        assertEquals(Map.of(WOOD, -2_000L), paused.currentRateDelta());
        assertEquals(Map.of(WOOD, -2_000L), paused.nominalRateDelta());
        assertEquals(Map.of(BOARDS, 4_000L, WOOD, -1_000L),
                new CommandHandler(state(Map.of(WOOD, 100 * UNIT))).preview(request(2, new Command.Build(SAWMILL, P2)))
                        .currentRateDelta());
    }

    @Test
    void currentRateOfUpgradeAppliesFromNextStepAndPaysCostFirst() {
        CommandHandler h = new CommandHandler(state(Map.of(WOOD, 100 * UNIT), new Building(1, FOREST, P1, 1, false, 0)));
        CommandHandler.Preview p = h.preview(upgrade(1, 2));
        assertNull(p.rejection());
        assertEquals(Map.of(WOOD, 1_000L), p.currentRateDelta());
        assertEquals(Map.of(WOOD, 1_000L), p.nominalRateDelta());
    }

    @Test
    void currentRateOfStorageReflectsCapacityForAFullForest() {
        EconomyState full = new EconomyState(REALM, 0, Map.of(WOOD, EconomyState.CAPACITY_START), Map.of(),
                List.of(new Building(1, FOREST, P1, 1, false, 0)), OPEN);
        assertEquals(Map.of(WOOD, 2_000L), new CommandHandler(full).preview(request(1, new Command.Storage(WOOD))).currentRateDelta());
    }

    @Test
    void snapshotWithRequestRecordWithoutStatusIsRejected() {
        EconomyState st = state(Map.of(WOOD, 100 * UNIT));
        EconomySnapshot s = st.snapshot();
        EconomySnapshot broken = new EconomySnapshot(s.realmId(), s.epoch(), s.anchor(), s.revision(), s.stock(), s.capacity(),
                s.storageDone(), s.unlockedPlots(), s.buildings(), s.pending(), s.nextSeq(), s.nextId(),
                Map.of(new UUID(0, 9), new EconomyState.RequestRecord(null, null)));
        EconomyException e = assertThrows(EconomyException.class, () -> EconomyState.restore(broken, OPEN));
        assertEquals(EconomyException.Reason.INVALID_SNAPSHOT, e.reason());
    }

    @Test
    void adapterFailureAfterLongAbsenceKeepsAnchorAndQueueTimes() {
        boolean[] broken = {true};
        PlotZone flaky = plot -> {
            if (broken[0]) throw new IllegalStateException("Welt nicht erreichbar");
            return true;
        };
        EconomyState st = new EconomyState(REALM, 0, Map.of(WOOD, 100 * UNIT), Map.of(),
                List.of(new Building(1, FOREST, P1, 1, false, 0)), flaky);
        CommandHandler h = new CommandHandler(st);
        h.submit(request(1, new Command.Build(FOREST, P3)), 0);
        long absence = 10 * 3_600_000L;
        assertThrows(IllegalStateException.class, () -> h.advanceTo(absence));
        assertEquals(0, st.anchor());
        assertEquals(0L, st.snapshot().pending().get(0).at());
        assertEquals(100 * UNIT, st.stock(WOOD));
        broken[0] = false;
        h.advanceTo(absence);
        assertEquals(RequestStatus.APPLIED, status(h, 1).status());
    }

    /** RF-7: Pause #1 bei 2.300 ms, Upgrade #1 bei 2.700 ms, Bau #2 bei 2.900 ms, Fortsetzen bei 5.000 ms. */
    private static void scheduleRf7(CommandHandler h) {
        h.submit(request(1, new Command.Pause(1)), 2_300);
        h.submit(upgrade(2, 2), 2_700);
        h.submit(request(3, new Command.Build(FOREST, P3)), 2_900);
        h.submit(request(4, new Command.Resume(1)), 5_000);
    }

    private static Result status(CommandHandler h, long id) {
        return h.status(new UUID(0, id)).orElseThrow();
    }

    private static EconomyState state(Map<Resource, Long> stock, Building... buildings) {
        return new EconomyState(REALM, 0, stock, Map.of(), List.of(buildings), OPEN);
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
