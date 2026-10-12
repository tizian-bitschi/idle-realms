package idle_realms.core;

import static idle_realms.core.BuildingKind.FOREST;
import static idle_realms.core.BuildingKind.SAWMILL;
import static idle_realms.core.EconomyState.CAPACITY_MAX;
import static idle_realms.core.EconomyState.CAPACITY_START;
import static idle_realms.core.EconomyState.CAPACITY_UPGRADE;
import static idle_realms.core.EconomyState.STEP_MS;
import static idle_realms.core.EconomyState.UNIT;
import static idle_realms.core.Plot.E1;
import static idle_realms.core.Plot.P1;
import static idle_realms.core.Plot.P2;
import static idle_realms.core.Plot.P3;
import static idle_realms.core.Plot.P4;
import static idle_realms.core.Resource.BOARDS;
import static idle_realms.core.Resource.WOOD;
import static idle_realms.core.StepReport.Status.MISSING_INPUT;
import static idle_realms.core.StepReport.Status.OUTPUT_FULL;
import static idle_realms.core.StepReport.Status.PAUSED;
import static idle_realms.core.StepReport.Status.RUNNING;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import idle_realms.core.EconomyException.Reason;
import idle_realms.core.EconomyState.PendingCommand;
import idle_realms.core.EconomyState.Rejection;
import idle_realms.core.StepReport.Status;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Referenzfälle RF-1 bis RF-17 aus ADR 0002, Abschnitt 11, ohne Minecraft-Client. RF-16 prüft den Snapshot
 * ({@link EconomySnapshot}); die Speicherung selbst liegt beim Adapter.
 */
class EconomyStateTest {
    private static final UUID REALM = new UUID(1, 2);
    private static final long HOUR_MS = 3_600_000L;

    private static final Map<String, Command> PURCHASE = Map.of(
            "SAW", new Command.Build(SAWMILL, P2),
            "F2", new Command.Build(FOREST, P3),
            "E1", new Command.Expand());
    private static final Map<String, Map<Resource, Long>> COST = Map.of(
            "SAW", Map.of(WOOD, 60 * UNIT),
            "F2", Map.of(WOOD, 40 * UNIT),
            "E1", Map.of(WOOD, 120 * UNIT, BOARDS, 40 * UNIT));

    private record Planned(String name, long at) {}

    @Test
    void rf1SawmillWaitsForWoodFromPausedForest() {
        EconomyState st = state(Map.of(), Map.of(), sawmill(1, P2), new Building(2, FOREST, P1, 1, true, 0));
        st.command(3_000, new Command.Resume(2));
        List<StepReport> r = st.advanceTo(5_000);
        assertStep(r.get(0), 0, 0, 0, MISSING_INPUT, PAUSED);
        assertStep(r.get(1), 1_000, 0, 0, MISSING_INPUT, PAUSED);
        assertStep(r.get(2), 2_000, 0, 0, MISSING_INPUT, PAUSED);
        assertStep(r.get(3), 3_000, 2_000, 0, MISSING_INPUT, RUNNING);
        assertStep(r.get(4), 4_000, 3_000, 4_000, RUNNING, RUNNING);
    }

    @Test
    void rf2FullBoardStoragePreventsConsumption() {
        EconomyState st = state(Map.of(WOOD, 5 * UNIT, BOARDS, CAPACITY_START), Map.of(), sawmill(1, P2));
        List<StepReport> r = st.advanceTo(3_000);
        for (int i = 0; i < 3; i++) {
            assertStep(r.get(i), i * STEP_MS, 5_000, CAPACITY_START, OUTPUT_FULL);
        }
    }

    @Test
    void rf3PauseAndResumeTakeEffectAtTheNextStep() {
        EconomyState st = state(Map.of(WOOD, 10 * UNIT), Map.of(), sawmill(1, P2));
        st.command(1_500, new Command.Pause(1));
        st.command(4_200, new Command.Resume(1));
        List<StepReport> r = st.advanceTo(6_000);
        assertStep(r.get(0), 0, 9_000, 4_000, RUNNING);
        assertStep(r.get(1), 1_000, 8_000, 8_000, RUNNING);
        assertStep(r.get(2), 2_000, 8_000, 8_000, PAUSED);
        assertStep(r.get(3), 3_000, 8_000, 8_000, PAUSED);
        assertStep(r.get(4), 4_000, 8_000, 8_000, PAUSED);
        assertStep(r.get(5), 5_000, 7_000, 12_000, RUNNING);
    }

    @Test
    void rf4ConcurrentSawmillsTakeInputByIdOrder() {
        EconomyState st = state(Map.of(WOOD, 1_500L), Map.of(), sawmill(1, P2), sawmill(2, P4));
        List<StepReport> r = st.advanceTo(2_000);
        assertStep(r.get(0), 0, 0, 6_000, RUNNING, RUNNING);
        assertStep(r.get(1), 1_000, 0, 6_000, MISSING_INPUT, MISSING_INPUT);
        assertEquals(List.of(1_000L, 500L), consumedPerRow(r.get(0)));
        assertEquals(List.of(4_000L, 2_000L), producedPerRow(r.get(0)));
    }

    @Test
    void rf4DistributionDoesNotDependOnListOrder() {
        List<StepReport> normal = state(Map.of(WOOD, 1_500L), Map.of(), sawmill(1, P2), sawmill(2, P4)).advanceTo(2_000);
        List<StepReport> reversed = state(Map.of(WOOD, 1_500L), Map.of(), sawmill(2, P4), sawmill(1, P2)).advanceTo(2_000);
        assertEquals(normal, reversed);
    }

    @Test
    void rf5TwoProducersShareOneFreeSlotByIdOrder() {
        EconomyState st = state(Map.of(WOOD, 1_199 * UNIT), Map.of(), forest(1, P1), forest(2, P3));
        List<StepReport> r = st.advanceTo(2_000);
        assertStep(r.get(0), 0, CAPACITY_START, 0, RUNNING, OUTPUT_FULL);
        assertStep(r.get(1), 1_000, CAPACITY_START, 0, OUTPUT_FULL, OUTPUT_FULL);
    }

    @Test
    void rf6SplittingTheSameTimeChangesNothing() {
        long end = 600_000;
        EconomyState reference = producers();
        List<StepReport> referenceReports = reference.advanceTo(end);
        Map<Integer, Long> referenceProduced = producedPerBuilding(referenceReports);
        int seed = 1;
        for (int trial = 0; trial < 200; trial++) {
            EconomyState st = producers();
            List<StepReport> all = new ArrayList<>();
            long now = 0;
            while (now < end) {
                seed = seed * 1_664_525 + 1_013_904_223;
                now = Math.min(end, now + 1 + ((Integer.toUnsignedLong(seed) * 7_919L) >>> 32));
                all.addAll(st.advanceTo(now));
            }
            assertEquals(reference.stock(WOOD), st.stock(WOOD), "Zerlegung " + trial + ": Holz");
            assertEquals(reference.stock(BOARDS), st.stock(BOARDS), "Zerlegung " + trial + ": Bretter");
            assertEquals(reference.anchor(), st.anchor(), "Zerlegung " + trial + ": Anker");
            assertEquals(reference.revision(), st.revision(), "Zerlegung " + trial + ": Schritte");
            assertEquals(referenceProduced, producedPerBuilding(all), "Zerlegung " + trial + ": Produktion je Betrieb");
            assertEquals(referenceReports, all, "Zerlegung " + trial + ": Schrittberichte");
            assertEquals(reference.snapshot(), st.snapshot(), "Zerlegung " + trial + ": Snapshot");
        }

        EconomyState whole = state(Map.of(), Map.of(), forest(1, P1));
        List<StepReport> wholeReports = whole.advanceTo(4_000);
        EconomyState split = state(Map.of(), Map.of(), forest(1, P1));
        List<StepReport> splitReports = new ArrayList<>(split.advanceTo(2_500));
        splitReports.addAll(split.advanceTo(4_000));
        assertEquals(8_000, whole.stock(WOOD));
        assertEquals(8_000, split.stock(WOOD));
        assertEquals(wholeReports.size(), splitReports.size());
    }

    @Test
    void rf7PauseUpgradeAndBuildInTheSameInterval() {
        EconomyState st = state(Map.of(WOOD, 500 * UNIT), Map.of(), forest(1, P1));
        st.advanceTo(2_000);
        st.command(2_300, new Command.Pause(1));
        st.command(2_700, new Command.Upgrade(1));
        st.command(2_900, new Command.Build(FOREST, P3));
        st.command(5_000, new Command.Resume(1));
        List<StepReport> r = st.advanceTo(7_000);
        assertStep(r.get(0), 2_000, 506_000, 0, RUNNING);
        assertStep(r.get(1), 3_000, 406_000, 0, PAUSED, RUNNING);
        assertEquals(List.of(false, true), startingUp(r.get(1)));
        assertStep(r.get(2), 4_000, 408_000, 0, PAUSED, RUNNING);
        assertStep(r.get(3), 5_000, 413_000, 0, RUNNING, RUNNING);
        assertStep(r.get(4), 6_000, 418_000, 0, RUNNING, RUNNING);
    }

    @Test
    void rf8NewBuildingProducesOnlyAfterStartup() {
        EconomyState st = state(Map.of(), Map.of(), forest(1, P1));
        st.advanceTo(50_000);
        st.command(50_000, new Command.Build(FOREST, P3));
        List<StepReport> r = st.advanceTo(53_000);
        assertStep(r.get(0), 50_000, 62_000, 0, RUNNING, RUNNING);
        assertEquals(List.of(false, true), startingUp(r.get(0)));
        assertStep(r.get(1), 51_000, 66_000, 0, RUNNING, RUNNING);
        assertStep(r.get(2), 52_000, 70_000, 0, RUNNING, RUNNING);
    }

    @Test
    void rf9ExamplePathReachesExpansionAtSeventyFiveSeconds() {
        List<List<String>> orders = List.of(
                List.of("SAW", "E1"),
                List.of("F2", "SAW", "E1"),
                List.of("SAW", "F2", "E1"),
                List.of("F2", "E1", "SAW"),
                List.of("SAW", "E1", "F2"));
        List<Planned> best = null;
        long bestE1 = Long.MAX_VALUE;
        for (List<String> order : orders) {
            List<Planned> plan = earliestPlan(order);
            if (plan == null) continue;
            long e1 = plan.stream().filter(p -> p.name().equals("E1")).findFirst().orElseThrow().at();
            if (e1 < bestE1) {
                bestE1 = e1;
                best = plan;
            }
        }
        assertEquals(List.of(new Planned("F2", 20_000), new Planned("SAW", 36_000), new Planned("E1", 75_000)), best);

        EconomyState st = state(Map.of(), Map.of(), forest(1, P1));
        for (Planned p : best) {
            st.command(p.at(), PURCHASE.get(p.name()));
        }
        List<StepReport> r = st.advanceTo(bestE1 + STEP_MS);
        assertTrue(r.stream().flatMap(x -> x.commands().stream()).allMatch(a -> a.rejection() == null));
    }

    @Test
    void rf10EpochOffGridKeepsStepBoundaries() {
        EconomyState st = state(250, Map.of(), Map.of(), forest(1, P1));
        st.command(1_501, new Command.Pause(1));
        List<StepReport> r = st.advanceTo(5_000);
        assertStep(r.get(0), 250, 2_000, 0, RUNNING);
        assertStep(r.get(1), 1_250, 4_000, 0, RUNNING);
        assertStep(r.get(2), 2_250, 4_000, 0, PAUSED);
        assertStep(r.get(3), 3_250, 4_000, 0, PAUSED);
        assertEquals(1, r.get(2).commands().size());
    }

    @Test
    void rf11AbsenceIsCappedAtEightHours() {
        EconomyState st = state(Map.of(), Map.of(WOOD, CAPACITY_MAX), forest(1, P1));
        List<StepReport> r = st.advanceTo(10 * HOUR_MS);
        assertEquals(28_800, r.size());
        assertEquals(7_200_000, r.get(0).at());
        assertEquals(28_800 * 2_000L, st.stock(WOOD));
    }

    @Test
    void rf12NegativeTimeDifferenceCreatesNoStepAndKeepsAnchor() {
        EconomyState st = state(Map.of(), Map.of(), forest(1, P1));
        st.advanceTo(5_000);
        assertEquals(5_000, st.anchor());
        assertEquals(List.of(), st.advanceTo(3_000));
        assertEquals(5_000, st.anchor());
        assertEquals(1, st.advanceTo(6_000).size());
        assertEquals(12_000, st.stock(WOOD));
    }

    @Test
    void rf13PendingCommandSurvivesMovingTheAnchor() {
        EconomyState st = state(Map.of(), Map.of(), forest(1, P1));
        st.command(1_000, new Command.Pause(1));
        List<StepReport> r = st.advanceTo(10 * HOUR_MS);
        assertEquals(7_200_000, r.get(0).at());
        assertEquals(new Command.Pause(1), r.get(0).commands().get(0).command());
        assertNull(r.get(0).commands().get(0).rejection());
        assertEquals(0, st.stock(WOOD));
    }

    @Test
    void rf14RejectedCommandsLeaveStateUnchanged() {
        EconomyState st = state(Map.of(WOOD, 500 * UNIT), Map.of(WOOD, 5_000 * UNIT), forest(1, P1));
        st.command(0, new Command.Storage(WOOD));
        st.command(0, new Command.Storage(WOOD));
        st.command(0, new Command.Pause(99));
        st.command(0, new Command.Build(SAWMILL, P1));
        st.command(0, new Command.Build(FOREST, P2));
        st.command(0, new Command.Resume(1));
        List<StepReport> r = st.advanceTo(1_000);
        assertEquals(
                Arrays.asList(null, Rejection.STORAGE_DONE, Rejection.UNKNOWN_BUILDING, Rejection.PLOT_OCCUPIED,
                        Rejection.PLOT_RESERVED, Rejection.NOT_PAUSED),
                r.get(0).commands().stream().map(StepReport.Applied::rejection).toList());
        assertEquals(5_000 * UNIT + CAPACITY_UPGRADE, st.capacity(WOOD));
        assertEquals(500 * UNIT - 40 * UNIT + 2_000, st.stock(WOOD));
    }

    @Test
    void rf15StockAboveCapacityAbortsStepWithoutChange() {
        EconomyState st = state(Map.of(WOOD, 2_000L), Map.of(WOOD, 1_000L), forest(1, P1));
        EconomyException e = assertThrows(EconomyException.class, () -> st.advanceTo(1_000));
        assertEquals(Reason.STOCK_ABOVE_CAPACITY, e.reason());
        assertEquals(0, st.anchor());
        assertEquals(0, st.revision());
        assertEquals(2_000, st.stock(WOOD));
    }

    @Test
    void rf17StorageUpgradeBeyondMaximumIsRejectedBeforeAnyChange() {
        EconomyState st = state(Map.of(WOOD, 500 * UNIT), Map.of(WOOD, CAPACITY_MAX), forest(1, P1));
        st.command(0, new Command.Storage(WOOD));
        List<StepReport> r = st.advanceTo(1_000);
        assertEquals(Rejection.CAPACITY_LIMIT, r.get(0).commands().get(0).rejection());
        assertEquals(CAPACITY_MAX, st.capacity(WOOD));
        assertEquals(500 * UNIT + 2_000, st.stock(WOOD));
    }

    @Test
    void invalidDefinitionsAreRejectedWithStructuredError() {
        assertEquals(Reason.INVALID_AMOUNT,
                assertThrows(EconomyException.class, () -> state(Map.of(WOOD, -1L), Map.of(), forest(1, P1))).reason());
        assertEquals(Reason.INVALID_AMOUNT,
                assertThrows(EconomyException.class, () -> state(Map.of(), Map.of(WOOD, CAPACITY_MAX + 1), forest(1, P1)))
                        .reason());
        assertEquals(Reason.INVALID_BUILDING,
                assertThrows(EconomyException.class, () -> state(Map.of(), Map.of(), forest(1, P2))).reason());
        assertEquals(Reason.INVALID_BUILDING,
                assertThrows(EconomyException.class, () -> state(Map.of(), Map.of(), forest(1, P1), forest(1, P3)))
                        .reason());
        assertEquals(Reason.INVALID_BUILDING,
                assertThrows(EconomyException.class, () -> state(Map.of(), Map.of(), forest(1, P1), forest(2, P1)))
                        .reason());
        assertEquals(Reason.INVALID_BUILDING,
                assertThrows(EconomyException.class, () -> state(Map.of(), Map.of(),
                        new Building(1, FOREST, P1, 3, false, 0))).reason());
    }

    @Test
    void rf16SavedStateKeepsStorageSequenceAndPendingCommands() {
        EconomyState st = state(Map.of(WOOD, 100 * UNIT), Map.of(), forest(1, P1));
        st.command(0, new Command.Storage(WOOD));
        st.advanceTo(1_000);
        st.command(1_000, new Command.Pause(1));
        EconomyState loaded = EconomyState.restore(st.snapshot());
        loaded.command(1_000, new Command.Storage(WOOD));
        loaded.command(1_000, new Command.Resume(1));
        List<StepReport> r = loaded.advanceTo(2_000);
        assertEquals(Arrays.asList(null, Rejection.STORAGE_DONE, null),
                r.get(0).commands().stream().map(StepReport.Applied::rejection).toList());
        assertEquals(2 * CAPACITY_START, loaded.capacity(WOOD));
        assertFalse(loaded.buildings().get(0).paused());
    }

    @Test
    void snapshotRoundTripReproducesStateAndFutureSteps() {
        EconomyState st = state(Map.of(WOOD, 500 * UNIT), Map.of(), forest(1, P1), sawmill(2, P2));
        st.advanceTo(3_000);
        st.command(3_500, new Command.Upgrade(1));
        st.command(4_200, new Command.Pause(2));
        st.advanceTo(4_300);
        EconomySnapshot snap = st.snapshot();
        EconomyState restored = EconomyState.restore(snap);
        assertEquals(snap, restored.snapshot());
        assertEquals(st.advanceTo(40_000), restored.advanceTo(40_000));
        assertEquals(st.snapshot(), restored.snapshot());
    }

    @Test
    void snapshotKeepsLastStepReportsOfBuildings() {
        EconomyState st = state(Map.of(), Map.of(), sawmill(1, P2), new Building(2, FOREST, P1, 1, true, 0));
        st.advanceTo(3_000);
        EconomyState restored = EconomyState.restore(st.snapshot());
        assertEquals(st.buildings(), restored.buildings());
        assertEquals(MISSING_INPUT, restored.buildings().get(0).last().status());
        assertEquals(PAUSED, restored.buildings().get(1).last().status());
        assertEquals(st.advanceTo(5_000), restored.advanceTo(5_000));
    }

    @Test
    void restoredPendingCommandsAreValidatedAndOrdered() {
        EconomySnapshot base = state(Map.of(), Map.of(), forest(1, P1)).snapshot();
        Map<Resource, Long> stock = base.stock();
        Map<Resource, Long> capacity = base.capacity();
        assertEquals(Reason.INVALID_SNAPSHOT, invalidReason(snapshotWith(base, 0, stock, capacity,
                List.of(new PendingCommand(0, 1, new Command.Storage(null))), 2, 2)));
        assertEquals(Reason.INVALID_SNAPSHOT, invalidReason(snapshotWith(base, 0, stock, capacity,
                List.of(new PendingCommand(500, 1, new Command.Pause(1))), 2, 2)));
        assertEquals(Reason.INVALID_SNAPSHOT, invalidReason(snapshotWith(base, 0, stock, capacity,
                List.of(new PendingCommand(0, 2, new Command.Resume(1)), new PendingCommand(0, 1, new Command.Pause(1))), 3, 2)));
        assertEquals(Reason.INVALID_SNAPSHOT, invalidReason(snapshotWith(base, 0, stock, capacity,
                List.of(new PendingCommand(0, 1, new Command.Pause(1)), new PendingCommand(0, 1, new Command.Resume(1))), 2, 2)));
    }

    @Test
    void restoredSnapshotRejectsMissingResourceAndInconsistentStage() {
        EconomySnapshot base = state(Map.of(), Map.of(), forest(1, P1)).snapshot();
        assertEquals(Reason.INVALID_SNAPSHOT, invalidReason(snapshotWith(base, 0, Map.of(WOOD, 0L), base.capacity(), List.of(), 1, 2)));
        EconomyState.BuildingView wrongStage = new EconomyState.BuildingView(1, FOREST, P1, 1, false, 3, 0, 0, null);
        assertEquals(Reason.INVALID_SNAPSHOT, invalidReason(snapshotWithBuildings(base, List.of(wrongStage))));
    }

    @Test
    void counterLimitIsReachedOnlyBeforeAnyChange() {
        EconomySnapshot base = state(Map.of(), Map.of(), forest(1, P1)).snapshot();
        assertEquals(Reason.INVALID_SNAPSHOT, invalidReason(snapshotWith(base, 0, base.stock(), base.capacity(), List.of(),
                Long.MAX_VALUE, 2)));
        EconomyState st = EconomyState.restore(snapshotWith(base, 0, base.stock(), base.capacity(), List.of(),
                Long.MAX_VALUE - 1, 2));
        long revision = st.revision();
        EconomyException e = assertThrows(EconomyException.class, () -> st.command(0, new Command.Pause(1)));
        assertEquals(Reason.COUNTER_OVERFLOW, e.reason());
        assertEquals(revision, st.revision());
        assertEquals(List.of(), st.advanceTo(0));
    }

    @Test
    void negativeTimeDifferenceWithHugeGapCreatesNoStep() {
        EconomySnapshot base = state(Map.of(), Map.of(), forest(1, P1)).snapshot();
        long anchor = 9_223_372_036_854_775_000L;
        EconomyState st = EconomyState.restore(snapshotWith(base, anchor, base.stock(), base.capacity(), List.of(), 1, 2));
        assertEquals(List.of(), st.advanceTo(Long.MIN_VALUE + 1_000_000_000L));
        assertEquals(anchor, st.anchor());
        assertEquals(0, st.revision());
    }

    @Test
    void restoredBuildingRegisterFollowsTheRules() {
        EconomySnapshot base = state(Map.of(), Map.of(), forest(1, P1)).snapshot();
        EconomyState.BuildingView f3 = new EconomyState.BuildingView(3, FOREST, P3, 1, false, 0, 0, 0, null);
        EconomyState.BuildingView f4 = new EconomyState.BuildingView(4, FOREST, P4, 1, false, 0, 0, 0, null);
        EconomyState.BuildingView f5 = new EconomyState.BuildingView(5, FOREST, E1, 1, false, 0, 0, 0, null);
        EconomyState.BuildingView f1 = new EconomyState.BuildingView(1, FOREST, P1, 1, false, 0, 0, 0, null);
        Set<Plot> withE1 = Set.of(P1, P2, P3, P4, E1);
        assertTrue(invalidMessage(withBuildings(base, withE1, List.of(f1, f3, f4, f5))).contains("Maximalzahl"));
        assertTrue(invalidMessage(withBuildings(base, Set.of(P1, P2, P3, P4), List.of(f1, f3, f5))).contains("gesperrtem Bauplatz"));
        EconomyState.BuildingView nullKind = new EconomyState.BuildingView(1, null, P1, 1, false, 0, 0, 0, null);
        assertTrue(invalidMessage(withBuildings(base, Set.of(P1, P2, P3, P4), List.of(nullKind))).contains("ohne Typ"));
        StepReport.Row negative = new StepReport.Row(1, Status.RUNNING, false, -5, 0);
        EconomyState.BuildingView badReport = new EconomyState.BuildingView(1, FOREST, P1, 1, false, 0, 0, 0, negative);
        assertTrue(invalidMessage(withBuildings(base, Set.of(P1, P2, P3, P4), List.of(badReport))).contains("außerhalb der Regel"));
    }

    @Test
    void restoredReportsMustAgreeWithStatusAndProduction() {
        EconomySnapshot base = state(Map.of(), Map.of(), forest(1, P1)).snapshot();
        Set<Plot> unlocked = Set.of(P1, P2, P3, P4);
        List<StepReport.Row> invalid = List.of(
                new StepReport.Row(1, null, false, 0, 0),
                new StepReport.Row(1, Status.MISSING_INPUT, false, 2_000, 0),
                new StepReport.Row(1, Status.RUNNING, false, 0, 0),
                new StepReport.Row(1, Status.MISSING_INPUT, false, 0, 0));
        for (StepReport.Row row : invalid) {
            assertEquals(Reason.INVALID_SNAPSHOT, invalidReason(withBuildings(base, unlocked, List.of(view(row)))));
        }
        StepReport.Row consistent = new StepReport.Row(1, Status.RUNNING, false, 2_000, 0);
        assertDoesNotThrow(() -> EconomyState.restore(withBuildings(base, unlocked, List.of(view(consistent)))));
    }

    @Test
    void idLimitIsReachedOnlyBeforeAnyChange() {
        EconomySnapshot base = state(Map.of(), Map.of(), forest(1, P1)).snapshot();
        EconomyState atLimit = EconomyState.restore(snapshotWith(base, 0, base.stock(), base.capacity(), List.of(), 1,
                Integer.MAX_VALUE - 1));
        atLimit.command(0, new Command.Build(FOREST, P3));
        assertEquals(Rejection.ID_EXHAUSTED, atLimit.advanceTo(1_000).get(0).commands().get(0).rejection());
        EconomyState beforeLimit = EconomyState.restore(snapshotWith(base, 0, Map.of(WOOD, 40 * UNIT, BOARDS, 0L),
                base.capacity(), List.of(), 1, Integer.MAX_VALUE - 2));
        beforeLimit.command(0, new Command.Build(FOREST, P3));
        assertNull(beforeLimit.advanceTo(1_000).get(0).commands().get(0).rejection());
        assertEquals(beforeLimit.snapshot(), EconomyState.restore(beforeLimit.snapshot()).snapshot());
    }

    private static EconomyState.BuildingView view(StepReport.Row row) {
        return new EconomyState.BuildingView(1, FOREST, P1, 1, false, 0, 0, 0, row);
    }

    private static String invalidMessage(EconomySnapshot s) {
        EconomyException e = assertThrows(EconomyException.class, () -> EconomyState.restore(s));
        assertEquals(Reason.INVALID_SNAPSHOT, e.reason());
        return e.getMessage();
    }

    private static EconomySnapshot withBuildings(EconomySnapshot s, Set<Plot> unlocked, List<EconomyState.BuildingView> buildings) {
        return new EconomySnapshot(s.realmId(), s.epoch(), s.anchor(), s.revision(), s.stock(), s.capacity(), s.storageDone(),
                unlocked, buildings, s.pending(), s.nextSeq(), 10);
    }

    private static EconomySnapshot snapshotWithBuildings(EconomySnapshot s, List<EconomyState.BuildingView> buildings) {
        return new EconomySnapshot(s.realmId(), s.epoch(), s.anchor(), s.revision(), s.stock(), s.capacity(), s.storageDone(),
                s.unlockedPlots(), buildings, s.pending(), s.nextSeq(), s.nextId());
    }

    @Test
    void inconsistentSnapshotIsRejectedWithoutCreatingState() {
        EconomySnapshot base = state(Map.of(), Map.of(), forest(1, P1)).snapshot();
        Map<Resource, Long> stock = base.stock();
        Map<Resource, Long> capacity = base.capacity();
        assertEquals(Reason.INVALID_SNAPSHOT, invalidReason(snapshotWith(base, 500, stock, capacity, List.of(), 1, 2)));
        assertEquals(Reason.INVALID_SNAPSHOT, invalidReason(snapshotWith(base, 1_000, stock, capacity,
                List.of(new PendingCommand(0, 1, new Command.Pause(1))), 2, 2)));
        assertEquals(Reason.INVALID_SNAPSHOT, invalidReason(snapshotWith(base, 0, stock, capacity,
                List.of(new PendingCommand(0, 5, new Command.Pause(1))), 3, 2)));
        assertEquals(Reason.INVALID_SNAPSHOT, invalidReason(snapshotWith(base, 0, stock, capacity, List.of(), 1, 1)));
        assertEquals(Reason.INVALID_SNAPSHOT, invalidReason(snapshotWith(base, 0, Map.of(WOOD, 2_000L, BOARDS, 0L),
                Map.of(WOOD, 1_000L, BOARDS, CAPACITY_START), List.of(), 1, 2)));
    }

    private static Reason invalidReason(EconomySnapshot s) {
        return assertThrows(EconomyException.class, () -> EconomyState.restore(s)).reason();
    }

    private static EconomySnapshot snapshotWith(EconomySnapshot s, long anchor, Map<Resource, Long> stock,
            Map<Resource, Long> capacity, List<PendingCommand> pending, long nextSeq, int nextId) {
        return new EconomySnapshot(s.realmId(), s.epoch(), anchor, s.revision(), stock, capacity, s.storageDone(),
                s.unlockedPlots(), s.buildings(), pending, nextSeq, nextId);
    }

    @Test
    void invalidCommandIsRejectedBeforeQueueingAndLeavesQueueIntact() {
        EconomyState st = state(Map.of(), Map.of(), forest(1, P1));
        st.command(0, new Command.Pause(1));
        long revision = st.revision();
        EconomyException e = assertThrows(EconomyException.class, () -> st.command(0, new Command.Storage(null)));
        assertEquals(Reason.INVALID_COMMAND, e.reason());
        assertEquals(revision, st.revision());
        List<StepReport> r = st.advanceTo(1_000);
        assertEquals(List.of(new Command.Pause(1)), r.get(0).commands().stream().map(StepReport.Applied::command).toList());
        assertNull(r.get(0).commands().get(0).rejection());
    }

    @Test
    void stockAboveCapacityLeavesAnchorUntouchedDuringLongAbsence() {
        EconomyState st = state(Map.of(WOOD, 2_000L), Map.of(WOOD, 1_000L), forest(1, P1));
        assertThrows(EconomyException.class, () -> st.advanceTo(10 * HOUR_MS));
        assertEquals(0, st.anchor());
        assertEquals(2_000, st.stock(WOOD));
    }

    @Test
    void timeBeyondLongRangeIsReportedAsStructuredError() {
        EconomyState st = state(Map.of(), Map.of(), forest(1, P1));
        EconomyException e = assertThrows(EconomyException.class, () -> st.command(Long.MAX_VALUE, new Command.Pause(1)));
        assertEquals(Reason.TIME_OUT_OF_RANGE, e.reason());
        assertDoesNotThrow(() -> st.advanceTo(Long.MAX_VALUE));
        assertTrue(st.anchor() > Long.MAX_VALUE - 2 * STEP_MS);
    }

    @Test
    void buildingViewAndUnlockedPlotsExposeTheRegister() {
        EconomyState st = EconomyState.start(REALM, 0);
        assertEquals(List.of(new EconomyState.BuildingView(1, FOREST, P1, 1, false, 0, 0, 0, null)), st.buildings());
        assertEquals(Set.of(P1, P2, P3, P4), st.unlockedPlots());
    }

    @Test
    void startProducesWoodFromTheEpoch() {
        EconomyState st = EconomyState.start(REALM, 250);
        List<StepReport> r = st.advanceTo(1_250);
        assertStep(r.get(0), 250, 2_000, 0, RUNNING);
    }

    /** Die fünf Verbraucher und Produzenten der Startregion; die Reihenfolge der Käufe ist nicht fest. */
    private static EconomyState producers() {
        return state(Map.of(), Map.of(), forest(1, P1), sawmill(2, P2));
    }

    /** Eingeschränkte Suche für RF-9: nur die genannten Käufe, jeder zum frühestmöglichen Schritt. */
    private static List<Planned> earliestPlan(List<String> order) {
        EconomyState st = state(Map.of(), Map.of(), forest(1, P1));
        List<Planned> plan = new ArrayList<>();
        int i = 0;
        for (long b = 0; i < order.size(); b += STEP_MS) {
            if (b > 600_000) return null;
            st.advanceTo(b);
            EnumMap<Resource, Long> spent = new EnumMap<>(Resource.class);
            while (i < order.size()) {
                String name = order.get(i);
                boolean affordable = COST.get(name).entrySet().stream()
                        .allMatch(e -> st.stock(e.getKey()) - spent.getOrDefault(e.getKey(), 0L) >= e.getValue());
                if (!affordable) break;
                COST.get(name).forEach((r, v) -> spent.merge(r, v, Long::sum));
                st.command(b, PURCHASE.get(name));
                plan.add(new Planned(name, b));
                i++;
            }
        }
        return plan;
    }

    private static EconomyState state(Map<Resource, Long> stock, Map<Resource, Long> capacity, Building... buildings) {
        return state(0, stock, capacity, buildings);
    }

    private static EconomyState state(long epoch, Map<Resource, Long> stock, Map<Resource, Long> capacity,
            Building... buildings) {
        return new EconomyState(REALM, epoch, stock, capacity, List.of(buildings));
    }

    private static Building forest(int id, Plot plot) {
        return new Building(id, FOREST, plot, 1, false, 0);
    }

    private static Building sawmill(int id, Plot plot) {
        return new Building(id, SAWMILL, plot, 1, false, 0);
    }

    private static void assertStep(StepReport r, long at, long wood, long boards, Status... statuses) {
        assertEquals(at, r.at());
        assertEquals(wood, r.stock().get(WOOD), "Holz bei " + at);
        assertEquals(boards, r.stock().get(BOARDS), "Bretter bei " + at);
        assertEquals(List.of(statuses), r.rows().stream().map(StepReport.Row::status).toList(), "Status bei " + at);
    }

    private static List<Boolean> startingUp(StepReport r) {
        return r.rows().stream().map(StepReport.Row::startingUp).toList();
    }

    private static List<Long> producedPerRow(StepReport r) {
        return r.rows().stream().map(StepReport.Row::produced).toList();
    }

    private static List<Long> consumedPerRow(StepReport r) {
        return r.rows().stream().map(StepReport.Row::consumed).toList();
    }

    private static Map<Integer, Long> producedPerBuilding(List<StepReport> reports) {
        Map<Integer, Long> sums = new TreeMap<>();
        for (StepReport r : reports) {
            for (StepReport.Row row : r.rows()) {
                sums.merge(row.buildingId(), row.produced(), Long::sum);
            }
        }
        return sums;
    }
}
