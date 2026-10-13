package idle_realms.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import idle_realms.core.Command;
import idle_realms.core.CommandHandler;
import idle_realms.core.CommandRequest;
import idle_realms.core.EconomySession.Readiness;
import idle_realms.core.EconomySnapshotText;
import idle_realms.core.EconomyState.RequestStatus;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Lebenszyklus gegen einen Speicher, der sich wie der Welt-Speicher verhält: fehlende, unlesbare und fehlgeschlagene Schreibvorgänge.
 * Der Welt-Speicher selbst (SavedDataStorage) braucht die NeoForge-Laufzeit und wird in einer Server-Testumgebung geprüft.
 */
class EconomyLifecycleTest {
    private static final UUID REALM = new UUID(7, 7);

    @Test
    void firstSaveThenRestartRestoresTheSameState() {
        MemoryStore world = new MemoryStore();
        EconomyLifecycle first = new EconomyLifecycle();
        first.attach(world, 0);
        assertEquals(Readiness.ABSENT, first.session().readiness());
        first.initialize(REALM, 0);
        first.tick(3_500);
        String saved = text(first);
        first.detach();

        EconomyLifecycle restarted = new EconomyLifecycle();
        restarted.attach(world, 3_500);

        assertEquals(Readiness.READY, restarted.session().readiness());
        assertEquals(saved, text(restarted));
    }

    @Test
    void unreadableFileFailsClosedAndIsNotOverwritten() {
        MemoryStore world = new MemoryStore();
        world.unreadable = true;

        EconomyLifecycle lifecycle = new EconomyLifecycle();
        lifecycle.attach(world, 0);

        assertEquals(Readiness.FAILED, lifecycle.session().readiness());
        assertThrows(IllegalStateException.class, () -> lifecycle.initialize(REALM, 0));
        lifecycle.detach();
        assertEquals(0, world.writes);
    }

    @Test
    void failedWriteKeepsThePreviousCompleteStateAndIsRetried() {
        MemoryStore world = new MemoryStore();
        EconomyLifecycle first = new EconomyLifecycle();
        first.attach(world, 0);
        first.initialize(REALM, 0);
        first.tick(3_500);
        String previous = text(first);
        first.detach();

        EconomyLifecycle second = new EconomyLifecycle();
        second.attach(world, 3_500);
        world.writesFail = true;
        second.tick(9_000);
        second.detach();
        assertEquals(previous, world.savedText());

        world.writesFail = false;
        EconomyLifecycle third = new EconomyLifecycle();
        third.attach(world, 3_500);
        third.tick(9_000);
        String latest = text(third);
        third.detach();
        EconomyLifecycle recovered = new EconomyLifecycle();
        recovered.attach(world, 9_000);
        assertEquals(latest, text(recovered));
    }

    @Test
    void pendingCommandIsRestoredOnceAfterRestart() {
        MemoryStore world = new MemoryStore();
        EconomyLifecycle first = new EconomyLifecycle();
        first.attach(world, 0);
        first.initialize(REALM, 0);
        first.session().commands().orElseThrow().submit(request(1, new Command.Pause(1)), 0);
        first.detach();

        EconomyLifecycle restarted = new EconomyLifecycle();
        restarted.attach(world, 5_000);
        CommandHandler handler = restarted.session().commands().orElseThrow();
        assertEquals(RequestStatus.APPLIED, handler.status(new UUID(0, 1)).orElseThrow().status());
        assertTrue(restarted.session().snapshot().orElseThrow().buildings().get(0).paused());

        handler.submit(request(2, new Command.Resume(1)), 5_000);
        restarted.tick(6_000);
        assertEquals(RequestStatus.APPLIED, handler.submit(request(1, new Command.Pause(1)), 6_000).status());
        assertFalse(restarted.session().snapshot().orElseThrow().buildings().get(0).paused());
    }

    private static String text(EconomyLifecycle lifecycle) {
        return EconomySnapshotText.encode(lifecycle.session().snapshot().orElseThrow());
    }

    private static CommandRequest request(long seq, Command command) {
        return new CommandRequest(new UUID(0, seq), REALM, CommandHandler.DEFINITION_REVISION, 0, command);
    }

    /** Verhält sich wie der Welt-Speicher: lesbar nur nach erfolgreichem Schreiben; ein Fehler lässt den letzten Stand stehen. */
    private static final class MemoryStore implements EconomyStore {
        private @Nullable EconomyData saved;
        private @Nullable EconomyData live;
        boolean unreadable;
        boolean writesFail;
        int writes;

        @Override
        public @Nullable EconomyData get() {
            return unreadable || saved == null ? null : new EconomyData(saved.text());
        }

        @Override
        public boolean fileExists() {
            return unreadable || saved != null;
        }

        @Override
        public void set(EconomyData data) {
            data.setDirty();
            live = data;
        }

        @Override
        public void scheduleSave() {
            flush();
        }

        @Override
        public void saveAndJoin() {
            flush();
        }

        String savedText() {
            return saved == null ? null : saved.text();
        }

        private void flush() {
            if (live == null || !live.isDirty()) return;
            // Vanilla löscht das Dirty-Flag vor dem Schreiben; ein Fehler hinterlässt den letzten vollständigen Stand.
            live.setDirty(false);
            if (writesFail) return;
            saved = new EconomyData(live.text());
            writes++;
        }
    }
}
