package idle_realms.core;

import java.util.Objects;
import java.util.UUID;

/**
 * Auftrag eines Clients an den {@link CommandHandler}. requestId verhindert die doppelte Anwendung, definitionRevision ist die
 * Preisrevision, die der Client gesehen hat, expectedStage ist die erwartete Zielstufe und gilt nur für Upgrade.
 */
public record CommandRequest(UUID requestId, UUID realmId, long definitionRevision, int expectedStage, Command command) {

    public CommandRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(realmId, "realmId");
        Objects.requireNonNull(command, "command");
    }
}
