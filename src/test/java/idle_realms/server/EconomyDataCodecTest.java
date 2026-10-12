package idle_realms.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

/** Prüft den tatsächlichen Plattform-Codec des Speicherplatzes: Schreiben in NBT und erneutes Lesen (Issue #7). */
class EconomyDataCodecTest {
    @Test
    void textBeyondTheNbtStringLimitSurvivesWriteAndRead() {
        String text = "idle-realms-economy 1\n" + "x".repeat(120_000);
        var codec = EconomyData.TYPE.codecFactory().create(null);

        Tag written = codec.encodeStart(NbtOps.INSTANCE, new EconomyData(text)).getOrThrow();
        EconomyData read = codec.parse(NbtOps.INSTANCE, written).getOrThrow();

        assertEquals(text, read.text());
    }
}
