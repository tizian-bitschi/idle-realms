package idle_realms.core;

import static idle_realms.core.BuildingKind.FOREST;
import static idle_realms.core.Plot.P3;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import idle_realms.core.EconomySession.Readiness;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Lebenszyklus der Wirtschaft aus Issue #7: Bereitschaft, eine Instanz je Sitzung, Wiederaufnahme und Fehler beim Laden. */
class EconomySessionTest {
    private static final UUID REALM = new UUID(1, 2);
    private static final PlotZone FREE = plot -> true;

    @Test
    void loadWithoutTimeProgressReturnsTheSameText() {
        String text = created(0).snapshotText().orElseThrow();

        EconomySession second = new EconomySession();
        second.load(text, FREE, 0);

        assertEquals(Readiness.READY, second.readiness());
        assertEquals(text, second.snapshotText().orElseThrow());
    }

    @Test
    void restartContinuesWithSameIdentitiesAndRemainders() {
        EconomySession running = created(250);
        running.commands().orElseThrow().submit(request(1, new Command.Build(FOREST, P3)), 1_000);
        running.advanceTo(4_750);
        String checkpoint = running.snapshotText().orElseThrow();

        EconomySession restarted = new EconomySession();
        restarted.load(checkpoint, FREE, 4_750);
        running.advanceTo(12_300);
        restarted.advanceTo(12_300);

        assertEquals(running.snapshotText(), restarted.snapshotText());
    }

    @Test
    void loadCatchesUpBeforeCommandsAreReleased() {
        String text = created(0).snapshotText().orElseThrow();

        EconomySession session = new EconomySession();
        session.load(text, FREE, 10_000);

        assertTrue(session.snapshotText().orElseThrow().contains("\nanchor 10000\n"));
        assertTrue(session.commands().isPresent());
    }

    @Test
    void absentStateAllowsCreateOnce() {
        EconomySession session = new EconomySession();
        session.load(null, FREE, 0);

        assertEquals(Readiness.ABSENT, session.readiness());
        assertTrue(session.commands().isEmpty());
        assertTrue(session.snapshotText().isEmpty());

        session.create(REALM, 0, FREE);

        assertEquals(Readiness.READY, session.readiness());
        assertThrows(IllegalStateException.class, () -> session.create(REALM, 0, FREE));
    }

    @Test
    void secondLoadIsRejectedUntilUnload() {
        EconomySession session = created(0);
        String text = session.snapshotText().orElseThrow();

        assertThrows(IllegalStateException.class, () -> session.load(text, FREE, 0));

        session.unload();
        assertEquals(Readiness.NOT_LOADED, session.readiness());
        session.load(text, FREE, 0);
        assertEquals(Readiness.READY, session.readiness());
    }

    @Test
    void failedLoadKeepsCommandsAndSavingClosed() {
        EconomySession session = new EconomySession();

        assertThrows(EconomyException.class, () -> session.load("kaputt\n", FREE, 0));

        assertEquals(Readiness.FAILED, session.readiness());
        assertTrue(session.commands().isEmpty());
        assertTrue(session.advanceTo(5_000).isEmpty());
        assertEquals(Optional.empty(), session.snapshotText());
    }

    @Test
    void unreadableStateBlocksTheSessionWithoutLoading() {
        EconomySession session = new EconomySession();

        session.fail();

        assertEquals(Readiness.FAILED, session.readiness());
        assertTrue(session.commands().isEmpty());
        assertThrows(IllegalStateException.class, () -> session.load(null, FREE, 0));
    }

    @Test
    void unloadDropsTheStateOfTheSession() {
        EconomySession session = created(0);

        session.unload();

        assertTrue(session.commands().isEmpty());
        assertTrue(session.snapshotText().isEmpty());
    }

    /** Eine Sitzung wie beim Serverstart ohne gespeicherten Stand: erst Laden ohne Stand, dann Anlegen. */
    private static EconomySession created(long epoch) {
        EconomySession session = new EconomySession();
        session.load(null, FREE, epoch);
        session.create(REALM, epoch, FREE);
        return session;
    }

    private static CommandRequest request(long id, Command command) {
        return new CommandRequest(new UUID(0, id), REALM, CommandHandler.DEFINITION_REVISION, 0, command);
    }
}
