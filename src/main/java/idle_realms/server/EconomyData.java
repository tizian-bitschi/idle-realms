package idle_realms.server;

import com.mojang.serialization.Codec;
import idle_realms.IdleRealms;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Speicherplatz des Wirtschaftsstands, einmal je Welt (ADR 0001). Gespeichert wird die Textform des Kerns als Byte-Array; ein
 * String-Tag wäre auf 65.535 Bytes begrenzt. Die Plattform schreibt die Datei atomar. Der Text wird nur übernommen, wenn der
 * Lifecycle ihn ausdrücklich adoptiert; so bleibt der geladene Stand unverändert, solange kein neuer Stand vorliegt.
 */
final class EconomyData extends SavedData {
    static final Identifier ID = Identifier.fromNamespaceAndPath(IdleRealms.MOD_ID, "economy");
    static final SavedDataType<EconomyData> TYPE = new SavedDataType<>(
            ID,
            () -> new EconomyData(null),
            Codec.BYTE_BUFFER.xmap(EconomyData::fromBytes, EconomyData::toBytes));

    private String text;

    EconomyData(String text) {
        this.text = text;
    }

    /** Datei dieses Speicherplatzes im Datenordner der Welt; so legt die Plattform sie selbst ab. */
    static Path fileIn(Path dataFolder) {
        return ID.withSuffix(".dat").resolveAgainst(dataFolder);
    }

    /** Aktuell gespeicherter Text: der geladene Stand oder der zuletzt übernommene. */
    String text() {
        return text;
    }

    /** Übernimmt einen neuen Stand und markiert ihn zum Speichern. */
    void adopt(String text) {
        this.text = text;
        setDirty();
    }

    private static EconomyData fromBytes(ByteBuffer bytes) {
        byte[] raw = new byte[bytes.remaining()];
        bytes.get(raw);
        return new EconomyData(new String(raw, StandardCharsets.UTF_8));
    }

    private static ByteBuffer toBytes(EconomyData data) {
        return ByteBuffer.wrap(data.text.getBytes(StandardCharsets.UTF_8));
    }
}
