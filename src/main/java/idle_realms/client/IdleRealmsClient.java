package idle_realms.client;

import idle_realms.IdleRealms;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

// Nur auf dem Client geladen. Der gemeinsame Einstieg referenziert diese Klasse nicht.
@Mod(value = IdleRealms.MOD_ID, dist = Dist.CLIENT)
public final class IdleRealmsClient {
    public IdleRealmsClient() {
        IdleRealms.LOGGER.info("Idle Realms Client gestartet");
    }
}
