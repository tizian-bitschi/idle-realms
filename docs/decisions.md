# Entscheidungen und offene Fragen

Stand: 11. Oktober 2026

Die Projektziele stammen aus dem Auftrag des Projektgründers. Das [Konzept v0.1](game-design-v0.1.md) enthält die bisherigen Arbeitsannahmen. Die zusätzlichen Vorschläge aus dem [Review des Idle-Kerns](idle-core-review.md) haben den Status Empfehlung; ihre Aufnahme ins Repository bestätigt sie noch nicht als Designentscheidungen.

## Projektziele

| ID | Ziel | Herkunft |
| --- | --- | --- |
| Z-01 | Minecraft wird zu einem Idle Game. Die Wirtschaft erzeugt den Fortschritt, der Spieler trifft Entscheidungen über Investitionen und Upgrades. | Projektauftrag |
| Z-02 | Die Welt bleibt begehbar. Arbeit, Gebäude und räumliche Entwicklung zeigen den Fortschritt beim Zuschauen und Herumlaufen. | Projektauftrag |
| Z-03 | Zuerst wird der Idle-Kern gefestigt. Die langfristige Erweiterung folgt auf einen funktionierenden Anfang. | Projektauftrag |

## Ideen des Projektgründers

| ID | Idee | Noch zu bestimmen |
| --- | --- | --- |
| I-01 | Golems bewegen sich durch die Welt und verrichten sichtbare Arbeit. Der Kupfergolem ist ein genannter Ausgangspunkt. | Aufgaben, Modelle, Bindung an Arbeitsplätze und Einfluss ihrer Bewegung auf die Wirtschaft. |
| I-02 | Zu Beginn stehen beispielsweise vier Chunks zur Verfügung. Weitere Flächen werden nach und nach freigeschaltet. | Startgröße, Art der Grenze, Kosten und Nutzen einer Erschließung. |

Beide Punkte wurden als Beispiele eingebracht. Die konkrete Ausführung ist offen.

## Arbeitsannahmen aus Konzept v0.1

Die Seitenangaben beziehen sich auf das [ursprüngliche Konzept](game-design-v0.1.md).

| ID | Annahme | Fundstelle und Klärungsbedarf |
| --- | --- | --- |
| K-01 | Total Conversion mit virtuellen Ressourcen und Gebäuden als Weltobjekten; Singleplayer zuerst. | S. 2 und 10. Edition, Minecraft-Version und Mod-Loader fehlen noch. |
| K-02 | Ein vollständiger Fortschrittspfad gelingt ohne manuelles Abbauen, Kämpfen oder Crafting. Aktive Erkundung und Gestaltung beschleunigen bestimmte Ziele. | S. 2 und 3. Art und Umfang des aktiven Vorteils sind offen. |
| K-03 | Produktion läuft unabhängig von geladenen Chunks. Offline-Zeit wird mit Inputs und Lagergrenzen nachgerechnet. | S. 10. Zeitmodell, Pausenverhalten und mögliche Obergrenzen sind offen. |
| K-04 | Eine Region besitzt zunächst vier Bauplätze; Biome und Nachbarschaften beeinflussen die Wirtschaft. | S. 6. Die erste Kette umfasst bereits sechs Produktionsgebäude sowie Weltenkern und Lagerhaus. Zusätzliche Plätze oder andere Belegungsregeln fehlen. |
| K-05 | Der Weltenatlas bündelt die Bedienung. Direktes Bauen in der Welt ist eine ergänzende Ansicht. | S. 9. Der Review empfiehlt eine größere Rolle für direkte Gebäudeinteraktion. |
| K-06 | Die erste spielbare Version endet bei automatisierter Eisenproduktion. Kosten, Raten und der 15-Minuten-Testlauf sind Platzhalter für Playtests. | S. 5 und 11. Die zusätzliche Holz/Bretter-Vorstufe aus dem Review ist noch nicht beschlossen. |
| K-07 | Spätere Ären führen zu Redstone, Nether, End und Epochenwechsel. Gebäudestrukturen bleiben beim Prestige erhalten, Gebäude-Level werden zurückgesetzt. | S. 7 und 8. Zwei bis vier Stunden bis zum ersten Epochenwechsel sind ein Designrichtwert. Das sichtbare Erscheinungsbild nach dem Reset ist offen. |

## Empfehlungen aus dem Review

Für alle folgenden Punkte gilt: vorgeschlagen, noch nicht ausdrücklich bestätigt. Die Begründungen und Rechenbeispiele stehen im [Review](idle-core-review.md).

| ID | Empfehlung | Zweck |
| --- | --- | --- |
| E-01 | Eine kleine, begehbare Siedlung aus automatisch arbeitenden Betrieben bildet den Anfang. | Das Spielversprechen lässt sich bereits mit wenigen Produktionsstellen prüfen. |
| E-02 | Ein Golem gehört zunächst fest zu seinem Betrieb. Der Gebäudekauf umfasst den Arbeiter; die erste Version erhält keinen frei zuweisbaren Arbeiterpool. | Kurze, verständliche Arbeitsabläufe halten die erste Umsetzung überschaubar. |
| E-03 | Simulation und Darstellung nutzen denselben Betriebszustand. Tatsächlich gelaufene Golem-Schritte bestimmen zunächst keine Erträge. | Produktion bleibt bei entladenen Chunks und offline berechenbar; sichtbare Arbeit passt zum aktuellen Zustand. |
| E-04 | Das Startgebiet umfasst versuchsweise vier Chunks in einer Anordnung von 2 × 2. Die erste Testregion ist fest gestaltet. | Gebäudegrundrisse, Laufwege und sichere Startressourcen lassen sich unter bekannten Bedingungen prüfen. |
| E-05 | Erschließung erlaubt die wirtschaftliche Nutzung weiterer Flächen. Die Umgebung bleibt begehbar. | Räumlicher Fortschritt erhält einen Nutzen, während Erkundung möglich bleibt. |
| E-06 | Vor der Eisenkette wird Holz, Bretter, Lager und eine Flächenerweiterung getestet. | Sichtbare Arbeit und unterschiedliche Investitionsfolgen werden früh überprüft. |
| E-07 | Der Startbetrieb ist regenerierend und kostenlos wiederherstellbar. Betriebe lassen sich im Prototyp verlustfrei versetzen oder zurückbauen; Verarbeitung ist kostenlos pausierbar. | Fehlkäufe und Materialknappheit führen nicht in eine Sackgasse. |
| E-08 | Betriebszustände erscheinen direkt am Gebäude. Größere Upgrades verändern Arbeitsplätze, Werkzeuge oder Anbauten dauerhaft. | Beim Rundgang sind Fortschritt und Produktionsprobleme erkennbar. |
| E-09 | Gebäude bieten direkte Interaktion; der Atlas ergänzt genaue Zahlen und spätere Fernverwaltung. | Entscheidungen lassen sich dort treffen, wo ihre Folgen sichtbar sind. |
| E-10 | Sichtbare Vorräte und Gebäudeteile besitzen getrennte Regeln gegenüber frei abbaubaren Gestaltungsblöcken. | Virtuelle Ressourcen gelangen beim Abbau ihrer Darstellung nicht doppelt in die Wirtschaft. |
| E-11 | Aktive Vorteile entstehen zunächst durch frühere Entscheidungen und Erkundung. Regelmäßige Golem-Pflege und notwendiges manuelles Farmen entfallen im ersten Test. | Längere Abwesenheit bleibt mit dem passiven Fortschrittspfad vereinbar. |

## Entschiedene Fragen

| ID | Entscheidung | Status |
| --- | --- | --- |
| O-01 | Java Edition, Zielversion, Mod-Loader und Java-Version. Festlegung, Begründung und Quellen stehen im [ADR 0001](architecture/0001-platform.md). | Entschieden am 11. Oktober 2026 |
| O-02 | Die Umsetzung beginnt mit der Holz/Bretter-Vorstufe (Empfehlung E-06). Die Eisenkette folgt danach. | Entschieden am 11. Oktober 2026; Umfang und Abnahme stehen im [ADR 0002](architecture/0002-m0-contract.md). |
| O-03 | Der Spieler baut nur auf festen Bauplätzen: P1 bis P4 in der Startregion und der Bauplatz E1 nach der Erweiterung. P2 ist ausschließlich für Sägewerke reserviert. | Entschieden am 11. Oktober 2026; [ADR 0002](architecture/0002-m0-contract.md), Abschnitt 7 und 8. Grundrisse sind Testwerte. |
| O-04 | Die Startgröße beträgt vier Chunks in einer Anordnung von 2 × 2 (E-04). Erschließung begrenzt Bauen und wirtschaftliche Nutzung, nicht die Bewegung (E-05). | Entschieden am 11. Oktober 2026; [ADR 0002](architecture/0002-m0-contract.md), Abschnitt 7.3. |
| O-05 | Ein Kupfergolem je Arbeiterslot, mit Vanilla-Modell als Basis. Ein Gebäudekauf bringt den Golem mit; er wechselt den Betrieb nicht (E-02). | Entschieden am 11. Oktober 2026; [ADR 0002](architecture/0002-m0-contract.md), Abschnitt 9. |
| O-06 | Entfernungen und Transporte haben im M0 keine wirtschaftliche Wirkung. Produktion hängt allein vom gespeicherten Betriebszustand ab (E-03). | Entschieden am 11. Oktober 2026; [ADR 0002](architecture/0002-m0-contract.md), Abschnitt 5. |
| O-07 | Fortschritt richtet sich nach der Systemuhr des Servers und rechnet in festen Schritten. Spielpause und geschlossene Sitzung zählen mit. Die Abwesenheit wird begrenzt; negative Zeitdifferenzen erzeugen keinen Ertrag. | Entschieden am 11. Oktober 2026; Zahlen als Festlegung bzw. Testwert, [ADR 0002](architecture/0002-m0-contract.md), Abschnitt 4. |
| O-08 | Die Lagerkapazität gilt je Ressource für die gesamte Welt. Ohne Eingriff füllt der Startforstbetrieb das Holzlager von leer aus in 600 s. | Entschieden am 11. Oktober 2026; Kapazitäten sind Testwerte, [ADR 0002](architecture/0002-m0-contract.md), Abschnitt 2 und 11 (RF-2, RF-5). |
| O-09 | Die erste Erweiterung schaltet Bauplatz E1 mit zwei zusätzlichen Chunks frei. Vor ihr stehen vier Bauplätze zur Verfügung; im M0 sind höchstens fünf Betriebe vorgesehen. | Entschieden am 11. Oktober 2026; Kosten sind Testwerte, [ADR 0002](architecture/0002-m0-contract.md), Abschnitt 3, 8 und RF-9. |

## Offene Entscheidungen

### Während der ersten Playtests

| ID | Frage | Prüfgrundlage |
| --- | --- | --- |
| O-10 | Welche Kosten und Produktionsraten erzeugen mehrere sinnvolle Investitionswege? | Unterschiedliche Ziele und Abwesenheitszeiten; Rechenbeispiele im Review. |
| O-11 | Welche Statusanzeigen versteht der Spieler beim Rundgang? Welche Zahlen braucht er erst bei Interaktion? | Arbeitszustände, Materialmangel und Lagerfüllung. |
| O-12 | Welche Ausbauschritte erhalten sichtbare Anbauten oder weitere Arbeiter? | Erkennbarkeit dauerhaften Fortschritts und Aufwand der Darstellung. |
| O-13 | Welche Vorteile bringt aktives Spielen, während der vollständige passive Fortschritt erhalten bleibt? | Erkundungsbelohnungen, frühere Entscheidungen und Wiederholungsaufwand. |

### Vor späteren Erweiterungen

| ID | Frage | Bezug |
| --- | --- | --- |
| O-14 | Wie wachsen Bauplätze und Dimensionen in bestehenden Welten? | Konzept v0.1, S. 12. |
| O-15 | Wie unterscheiden sich spätere Epochen, und wie sehen erhaltene Fabriken mit zurückgesetzten Leveln aus? | Konzept v0.1, S. 8; Review zum Prestige. |
| O-16 | Welche Regeln übernehmen später Baukäufe, Reserven und weitere Routineentscheidungen? | Konzept v0.1, S. 6, 7 und 12. Im ersten Vorschlag bleiben neue Investitionen beim Spieler. |

Die empfohlenen Arbeitsschritte und ihre Prüfbedingungen stehen im [Prototypplan](prototype-plan.md). Der [Projekteinstieg](../README.md) verweist auf die übrigen Unterlagen.
