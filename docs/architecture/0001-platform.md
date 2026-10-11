# ADR 0001: Zielplattform für M0

Stand: 11. Oktober 2026

Status: Angenommen. Entscheidet [Issue #2](https://github.com/tizian-bitschi/idle-realms/issues/2) und die Frage [O-01](../decisions.md).

## Entscheidung

| Bereich | Festlegung |
| --- | --- |
| Edition | Minecraft Java Edition |
| Minecraft-Version | 26.1.2 |
| Mod-Loader | NeoForge 26.1.2.116 (stabiler Build ohne `-beta`) |
| Sprachlaufzeit | Java 25, 64-Bit-JDK |
| Build | Gradle 9.1.0 oder neuer über den Wrapper im Repository; Gradle-Plugin ModDevGradle |
| Versionsquelle | `gradle.properties` ist die einzige Stelle mit Minecraft- und NeoForge-Version |
| Loader-Strategie | Nur NeoForge. Kein paralleler Support für Fabric oder Forge. |

## Begründung

**Java Edition.** Das Konzept sieht eine Total Conversion vor ([K-01](../decisions.md)). Die geprüften Schnittstellen für Entities, Renderer, Persistenz und Netzwerk gehören zur Java Edition. Bedrock ist damit ausgeschlossen.

**Minecraft 26.1.2 statt 26.3.** Mojang führt 26.3 als aktuelles Release im [Versionsmanifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json). Für 26.3 gibt es bei NeoForge jedoch nur `-beta`-Builds; bis zum letzten geprüften Stand war der neueste 26.3-Build `26.3.0.75-beta`. Das Suffix `-beta` bedeutet laut NeoForge, dass Breaking Changes noch erlaubt sind. Die NeoForge-Dokumentation ist außerdem mit Version 26.1 beschriftet. 26.1.2 besitzt einen stabilen NeoForge-Build (`26.1.2.116`) und passt zu dieser Dokumentation. Auch 26.2 besitzt einen stabilen Build (`26.2.0.90`); die geprüfte Dokumentation bezieht sich jedoch auf 26.1, deshalb fällt die Wahl auf 26.1.2.

**NeoForge.** NeoForge stellt die für M0 benötigten Schnittstellen bereit (siehe unten). Fabric wurde für diesen ADR nicht im selben Umfang geprüft. Ein Wechsel des Loaders wäre eine neue Entscheidung mit neuem ADR.

**Java 25 und Gradle 9.1.0.** Die NeoForge-Dokumentation verlangt für Minecraft 26.1 das JDK 25. Der [Release-Beitrag zu NeoForge 26.1](https://neoforged.net/news/26.1release/) nennt Java 25 und Gradle 9.1.0 oder neuer.

## Schnittstellen für M0

Alle Klassennamen stammen aus dem Quellpaket `neoforge-26.1.2.116-sources.jar` ([Maven](https://maven.neoforged.net/releases/net/neoforged/neoforge/26.1.2.116/neoforge-26.1.2.116-sources.jar)), sofern nicht anders angegeben.

| Anforderung | Schnittstelle | Nachweis |
| --- | --- | --- |
| Eigene Arbeiter-Entity | `EntityType.Builder` und `DeferredRegister.Entities` | [Dokumentation: Entities](https://docs.neoforged.net/docs/entities/) |
| Attribute des Arbeiters | `EntityAttributeCreationEvent` | Quellpaket `event/entity/EntityAttributeCreationEvent.java` |
| Darstellung des Arbeiters | `EntityRenderersEvent.RegisterRenderers#registerEntityRenderer` und `RegisterLayerDefinitions#registerLayerDefinition` | Quellpaket `client/event/EntityRenderersEvent.java` |
| Darstellung der Betriebsgebäude | Block-Entity mit `EntityRenderersEvent.RegisterRenderers#registerBlockEntityRenderer` | Quellpaket `client/event/EntityRenderersEvent.java` |
| Weltgebundener Zustand | `SavedData` mit `SavedDataType` und Codec | [Dokumentation: SavedData](https://docs.neoforged.net/docs/datastorage/saveddata/) |
| Server-Ticks | `ServerTickEvent.Pre` und `ServerTickEvent.Post` | Quellpaket `event/tick/ServerTickEvent.java` |
| Client-Server-Kommunikation | `RegisterPayloadHandlersEvent` mit `PayloadRegistrar.playToClient`, `playToServer` und `playBidirectional` | [Dokumentation: Networking](https://docs.neoforged.net/docs/networking/) und Quellpaket `network/registration/PayloadRegistrar.java` |
| Befehl für den Prototypstart | `RegisterCommandsEvent` | Quellpaket `event/RegisterCommandsEvent.java` |

Die Signatur von `SavedDataType` steht nur in der Dokumentation. Sie gehört zu Minecraft und nicht zum NeoForge-Quellpaket. Sie wird im ersten Gerüst gegen den Build geprüft.

## Startmethode für den Prototypspielstand

- **Art der Welt:** eine reguläre Einzelspielerwelt der Java Edition, die der Spieler selbst neu erstellt. Die Startregion und ihr Grundriss legt der M0-Vertrag fest.
- **Auslösender Schritt:** Der Spieler führt in dieser Welt den Befehl `/idlerealms prototyp start` aus. Der Befehl schreibt die Prototypdaten in die Welt und legt die Startregion an der Position des Spielers an.
- **Schutz vorhandener Welten:** Installation und Start der Mod verändern keine Welt. Der Befehl ist die einzige Stelle, die Prototypdaten anlegt. Existieren bereits Prototypdaten in der Welt, bricht der Befehl ab und überschreibt nichts.
- **Folge:** Der Spieler muss Befehle beim Erstellen der Welt aktivieren. Vorhandene Welten ohne Befehlsrechte können den Prototyp nicht starten, ohne neu erstellt zu werden.

## Grenzen im Code

- **Kern** (`core`): reine Java-Klassen für Wirtschaftsregeln, Betriebszustände und die Zeitberechnung. Der Kern importiert keine Typen aus `net.minecraft` oder `net.neoforged`.
- **Adapter** (`minecraft`): Entities, Renderer, `SavedData`-Codecs, Payloads, Tick-Registrierung und Befehle. Der Adapter ruft den Kern auf und übersetzt Minecraft-Zustand in Kernzustand.
- **Server:** Simulation, Persistenz und Payload-Handler für Anfragen vom Client.
- **Client:** Renderer und Payload-Handler für Anzeigen. Der Client-Code liegt in einem eigenen Paket und wird nur auf dem Client geladen.
- **Prüfung:** Ein Architekturtest prüft, dass der Kern keine Minecraft-Abhängigkeiten importiert. Der Test entsteht mit dem Gerüst.

## Frischer Checkout

- **Voraussetzungen:** JDK 25 (64-Bit), Git. Bei Gradle genügt der Wrapper aus dem Repository.
- **Build:** `./gradlew build`.
- **Erwartetes Artefakt:** eine Mod-JAR in `build/libs/`.
- **Entwicklungsstart:** `./gradlew runClient` für den Einzelspieler und `./gradlew runServer` für einen Dedicated Server.

Die Task-Namen folgen der ModDevGradle-Vorlage. Sie werden im ersten Build-Schritt bestätigt.

## Offene Prüfungen im Gerüst

1. Eine Payload erreicht im Einzelspielermodus den Client, obwohl der Server im selben Prozess läuft.
2. `./gradlew build` erzeugt die erwartete JAR, und `runClient` startet die Umgebung.
3. Die Signatur von `SavedDataType` und der Befehl `/idlerealms prototyp start` funktionieren mit dem gebauten Loader.

## Konsequenzen

- Die Spielversion liegt eine Stufe hinter dem aktuellen Mojang-Release 26.3.
- Ein Wechsel auf 26.3 ist sinnvoll, sobald NeoForge für 26.3 einen stabilen Build ohne `-beta` veröffentlicht und die Dokumentation die Version abdeckt. Dafür entsteht ein neuer ADR.
- Versionen stehen nur in `gradle.properties`. Dokumentation und Builddateien verweisen darauf und wiederholen die Zahlen nicht.

## Quellen

Abgerufen am 11. Oktober 2026.

- [Mojang: Versionsmanifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json): aktuelles Release 26.3, Snapshot 26.4-snapshot-3 und Release 26.1.2.
- [NeoForge: Maven-Metadaten](https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml): stabile Builds 26.1.2.116 und 26.2.0.90, Beta-Linie 26.3.0.x-beta.
- [NeoForge: Quellpaket 26.1.2.116](https://maven.neoforged.net/releases/net/neoforged/neoforge/26.1.2.116/neoforge-26.1.2.116-sources.jar): Klassen und Methoden der Tabelle oben.
- [NeoForge: Release für 26.1](https://neoforged.net/news/26.1release/): Java 25, Gradle 9.1.0, Bedeutung von `-beta`.
- [NeoForge: Erste Schritte](https://docs.neoforged.net/docs/gettingstarted/): JDK 25, ModDevGradle oder NeoGradle.
- [NeoForge: Entities](https://docs.neoforged.net/docs/entities/)
- [NeoForge: SavedData](https://docs.neoforged.net/docs/datastorage/saveddata/)
- [NeoForge: Networking](https://docs.neoforged.net/docs/networking/)

Die offizielle Minecraft-Seite zu Java Edition 26.3 war beim Erstellen dieses ADR nicht abrufbar. Die Aussagen zu 26.3 stützen sich auf das Versionsmanifest und die NeoForge-Maven-Metadaten.
