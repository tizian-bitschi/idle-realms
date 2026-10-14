package idle_realms.server;

import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import net.minecraft.world.level.storage.SavedDataStorage;

/** Welt-Speicher: delegiert an {@link SavedDataStorage} der Welt und nutzt dessen atomares Schreiben. */
final class SavedDataEconomyStore implements EconomyStore {
    private final SavedDataStorage storage;
    private final Path dataFolder;

    SavedDataEconomyStore(SavedDataStorage storage, Path dataFolder) {
        this.storage = storage;
        this.dataFolder = dataFolder;
    }

    @Override
    public @Nullable EconomyData get() {
        return storage.get(EconomyData.TYPE);
    }

    @Override
    public boolean fileExists() {
        return Files.exists(EconomyData.fileIn(dataFolder));
    }

    @Override
    public void set(EconomyData data) {
        storage.set(EconomyData.TYPE, data);
    }

    @Override
    public void scheduleSave() {
        storage.scheduleSave();
    }

    @Override
    public void saveAndJoin() {
        storage.saveAndJoin();
    }
}
