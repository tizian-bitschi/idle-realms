# Idle Realms

Eine Minecraft-Modifikation, in der eine automatisch produzierende Wirtschaft als begehbare, wachsende Siedlung sichtbar wird. Der Spieler beobachtet Arbeitsabläufe, entscheidet über Investitionen und erlebt die Folgen seiner Upgrades direkt in der Welt.

## Projektstand

Stand: 11. Oktober 2026. Das Projekt befindet sich in der Konzeptphase. Dieses Repository enthält den ursprünglichen Entwurf, die anschließende Konzeptprüfung und Empfehlungen für den ersten Prototyp. Eine spielbare Mod ist noch nicht implementiert. Die Zielplattform ist in [ADR 0001](docs/architecture/0001-platform.md) festgelegt.

Die Dokumentation unterscheidet ausdrücklich zwischen den Zielen des Projektinhabers, Festlegungen des ursprünglichen Konzeptentwurfs, späteren Empfehlungen und offenen Entscheidungen. Das Festhalten einer Empfehlung bedeutet noch keine verbindliche Übernahme.

## Ziel

Minecraft wird zu einem Idle Game, dessen Fortschritt in der Welt erkennbar ist. Ressourcen entstehen automatisch. Der Spieler läuft durch seine Siedlung, schaut den Betrieben bei der Arbeit zu und entscheidet über Ausbau und Expansion. Zunächst wird der Idle-Kern geprüft; die langfristig geplanten Systeme folgen auf dieser Grundlage.

Ausgangspunkt der Diskussion sind arbeitende Golems und ein kleines Startgebiet, das nach und nach wächst. Als erste Umsetzung wird eine Holz-Bretter-Wirtschaft mit sichtbaren Arbeitern, Lagergrenzen und einer Grundstückserweiterung empfohlen. Die im ursprünglichen Konzept vorgesehene Eisenproduktion bleibt als nächster Prüfschritt dokumentiert.

## Dokumentation

| Dokument | Inhalt |
|---|---|
| [Spielkonzept v0.1](docs/game-design-v0.1.md) | Vollständige redaktionelle Übertragung des ursprünglichen Entwurfs mit Baukosten, Produktionsketten, Ären und Langfristplanung. |
| [Prüfung und sichtbarer Idle-Kern](docs/idle-core-review.md) | Befunde zum Konzept, Golem-Arbeit, sichtbare Betriebszustände, Investitionsbeispiele, Flächenerweiterung und Offline-Fortschritt. |
| [Entscheidungsstand](docs/decisions.md) | Herkunft und Status der Vorgaben, Empfehlungen und offenen Fragen. |
| [Plan für den Prototyp](docs/prototype-plan.md) | Empfohlene Entwicklungsreihenfolge, begrenzter Umfang und überprüfbare Erfolgskriterien. |
| [Quellen und Referenzen](docs/references/README.md) | Herkunft der Unterlagen und offizielle Minecraft-Quellen. |
| [Originalkonzept als PDF](docs/references/Minecraft_Idle_Realms_Game_Design_v0.1.pdf) | Unveränderte Originaldatei vom 11. Oktober 2026. |
| [Angehängter Humanizer-Skill](docs/references/humanizer-skill.md) | Unveränderte Schreibreferenz aus der Konzeptdiskussion. |

## Nächster Arbeitsschritt

Die offenen Grundlagen für den ersten Prototyp klären: Bauflächen, Startproduktion, Grenzen der Weltgestaltung und Verhalten bei Abwesenheit. Danach prüft ein kleiner spielbarer Durchlauf, ob Produktion, Entscheidungen und sichtbare Veränderungen zusammen funktionieren. Umfang und Prüfkriterien stehen im [Prototypplan](docs/prototype-plan.md).

## Dokumentation weiterführen

Neue Entscheidungen erhalten einen ausdrücklichen Status und ein Datum im [Entscheidungsstand](docs/decisions.md). Der ursprüngliche Entwurf bleibt als historische Quelle erhalten. Neue Testwerte werden als Annahmen gekennzeichnet; Ergebnisse aus tatsächlichen Spieltests werden mit ihren Bedingungen festgehalten.
