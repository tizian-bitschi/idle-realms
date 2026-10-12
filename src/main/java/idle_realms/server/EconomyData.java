package idle_realms.server;

import com.mojang.serialization.Codec;
import idle_realms.IdleRealms;
import idle_realms.core.EconomySession;
import java.nio.file.Path;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Speicherplatz des Wirtschaftsstands, einmal je Welt (ADR 0001). Gespeichert wird die Textform des Kerns; die Plattform schreibt
 * die Datei atomar. Ohne laufende Sitzung liefert der Text den geladenen Stand unverändert zurück, damit ein Fehler die Quelle
 * nicht überschreibt.
 */
final class EconomyData extends SavedData {
    static final Identifier ID = Identifier.fromNamespaceAndPath(IdleRealms.MOD_ID, "economy");
    static final SavedDataType<EconomyData> TYPE = new SavedDataType<>(
            ID,
            () -> new EconomyData(null),
            Codec.STRING.xmap(EconomyData::new, EconomyData::text));

    private final String loaded;
    private EconomySession session;

    private EconomyData(String loaded) {
        this.loaded = loaded;
    }

    /** Datei dieses Speicherplatzes im Datenordner der Welt; so legt die Plattform sie selbst ab. */
    static Path fileIn(Path dataFolder) {
        return ID.withSuffix(".dat").resolveAgainst(dataFolder);
    }

    /** Text, wie er von der Platte kam. */
    String loaded() {
        return loaded;
    }

    /** Verbindet die bereite Sitzung; ab dann speichert der aktuelle Stand. */
    void attach(EconomySession session) {
        this.session = session;
    }

    private String text() {
        return session == null ? loaded : session.snapshotText().orElse(loaded);
    }
}
