package idle_realms;

import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Gemeinsamer Einstieg. Läuft auch im integrierten Server der Einzelspielerwelt, daher kein Client-Import hier.
@Mod(IdleRealms.MOD_ID)
public final class IdleRealms {
    public static final String MOD_ID = "idle_realms";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public IdleRealms() {
        LOGGER.info("Idle Realms gestartet");
    }
}
