# Prototypplan

Stand: 11. Oktober 2026

Status: Planungsvorschlag aus dem [Review des Idle-Kerns](idle-core-review.md). Der Projektgründer hat das Festhalten des Konzepts und der Besprechung beauftragt. Die Holz/Bretter-Vorstufe ist als M0-Umfang festgelegt (O-02, [ADR 0002](architecture/0002-m0-contract.md)). Die Eisenkette aus dem [Konzept v0.1](game-design-v0.1.md) folgt danach.

## 1. Regeln für den ersten Bau festlegen

Vor dem Aufbau des Mod-Projekts stehen die folgenden Entscheidungen an. Ihre IDs verweisen auf [Entscheidungen und offene Fragen](decisions.md).

- [x] Edition, Minecraft-Version und Mod-Loader wählen (O-01, entschieden in [ADR 0001](architecture/0001-platform.md)).
- [x] Den Umfang des ersten spielbaren Tests festlegen: Holz/Bretter-Vorstufe oder direkte Eisenkette (O-02, entschieden in [ADR 0002](architecture/0002-m0-contract.md)).
- [x] Bauweise, Startfläche und Bedeutung der Erschließungsgrenze bestimmen (O-03, O-04, O-09, [ADR 0002](architecture/0002-m0-contract.md)).
- [x] Golem-Rollen und den Einfluss von Transporten festlegen (O-05, O-06, [ADR 0002](architecture/0002-m0-contract.md)).
- [x] Fortschritt bei Pause und Abwesenheit sowie Lagergrenzen definieren (O-07, O-08, [ADR 0002](architecture/0002-m0-contract.md)).

Danach liegen ein Startgrundriss, eine Liste der ersten Betriebe und ein Ressourcenmodell mit Kosten, Verbrauch und Ertrag vor. Eine Skizze der Betriebszustände legt fest, was bei laufender Produktion, fehlendem Input, vollem Lager und manueller Pause sichtbar ist. Diese Ergebnisse reichen als Grundlage für die erste Implementierung.

## 2. Empfohlener erster Test: Holz und Bretter

Der Test prüft, ob automatische Arbeit in einer begehbaren Siedlung verständlich ist und ob Investitionen erkennbare Folgen haben. Er beginnt mit einer arbeitenden Holzquelle und endet mit einer erreichbaren Grundstückserweiterung.

### Vorgeschlagener Umfang

| Bestandteil | Verhalten im Test |
| --- | --- |
| Startgebiet | Fest gestaltete Region mit versuchsweise vier erschlossenen Chunks in einer Anordnung von 2 × 2; Platz für Laufwege und die ersten Betriebe. |
| Forstbetrieb | Zu Beginn bereits aktiv. Ein zugeordneter Golem zeigt einen wiederholbaren Arbeitsablauf; die Holzquelle regeneriert. |
| Sägewerk | Mit Startressourcen baubar. Es verbraucht Holz und erzeugt Bretter. Fehlendes Holz und ein volles Bretterlager verändern seinen sichtbaren Zustand. |
| Vorräte | Virtuelles Holz und virtuelle Bretter; das Testmodell verwendet getrennte Lagerkapazitäten. Sichtbare Stapel zeigen unterscheidbare Füllstände. |
| Investitionen | Wenige Verbesserungen für Förderung, Verarbeitung und Lager. Die Vorschau nennt sowohl zusätzlichen Ertrag als auch zusätzlichen Verbrauch. |
| Erweiterung | Eine neue Betriebsfläche ist als Ziel in der Welt erkennbar und mit bereits verfügbaren Ressourcen erreichbar. |
| Bedienung | Direkte Interaktion am Betrieb, eine kleine Ressourcenübersicht und eine Möglichkeit, Verarbeitung kostenlos zu pausieren. |
| Persistenz | Bestände, Ausbauten und Betriebszustände bleiben nach einem Neustart erhalten; Offline-Zeit berücksichtigt Inputs und Lagergrenzen. |
| Korrektur von Fehlkäufen | Verlustfreies Versetzen oder Zurückbauen im Prototyp und ein kostenlos wiederherstellbarer Startbetrieb. |

Größere Ausbaustufen verändern den Betrieb sichtbar, etwa durch einen zusätzlichen Arbeitsplatz oder einen Anbau. Die Simulation legt den aktuellen Betriebszustand fest. Der Golem zeigt diesen Zustand auch dann korrekt, wenn Material fehlt oder die Verarbeitung pausiert.

Die vollständige Eisenkette folgt im nächsten Abschnitt. Nether, End, Prestige, Multiplayer und komplexe Logistik bleiben wie im ursprünglichen v0.1 außerhalb des ersten Tests. Der Review empfiehlt außerdem, einen frei zuweisbaren Arbeiterpool, regelmäßige Golem-Pflege und die vollständige Atlas-Oberfläche zunächst zurückzustellen.

### Vorgeschlagener Ablauf

1. Beim Einstieg arbeitet der Forstbetrieb bereits. Der Spieler erkennt die Holzquelle und den wachsenden Vorrat.
2. Ein erstes Upgrade verändert die Arbeitsstelle und die Produktionsrate.
3. Der Spieler baut das Sägewerk. Holz fließt jetzt zugleich in Bauvorhaben und in die Verarbeitung.
4. Weitere Investitionen verbessern Förderung, Verarbeitung oder Lager. Ihre Folgen hängen vom nächsten Ziel und der geplanten Abwesenheit ab.
5. Die erste Erschließung verändert die nutzbare Fläche. Ein neuer Betrieb oder ein größerer Ausbau erhält dort Platz.

Zehn bis fünfzehn Minuten sind das vorgeschlagene Ziel für diesen Testdurchlauf. Die Kosten und Raten müssen diese Dauer erst in Playtests erreichen. Die Zahl beschreibt keine bereits gemessene Spielzeit.

### Prüfkriterien für den ersten Prototyp

Alle Punkte sind geplante Prüfungen. Es liegen noch keine Testergebnisse vor.

| Bereich | Prüfung | Erwartetes Ergebnis |
| --- | --- | --- |
| Verständlichkeit | Eine neue Testperson beobachtet Forstbetrieb und Sägewerk, bevor sie die Detailansichten öffnet. | Sie erkennt die Herkunft des Holzes und versteht, dass das Sägewerk Holz verarbeitet. |
| Zustände | Holz fehlt, das Ausgabelager ist voll und der Betrieb ist bewusst pausiert. | Darstellung und Detailansicht erklären denselben tatsächlichen Zustand; die passende Handlung ist auffindbar. |
| Sichtbarer Ausbau | Ein größeres Upgrade wird gekauft; die Testperson kehrt später an den Ort zurück. | Die Veränderung bleibt in der Welt erkennbar. |
| Investitionen | Derselbe Ausgangszustand wird mit verschiedenen nächsten Zielen oder Abwesenheitszeiten betrachtet. | Mindestens zwei Investitionen haben je nach Situation einen nachvollziehbaren Nutzen. Kaufvorschauen erklären die Auswirkungen auf die betroffene Kette. |
| Passiver Fortschritt | Der gesamte Anfang wird ohne manuelles Farmen gespielt. | Alle notwendigen Ressourcen und die erste Erweiterung sind erreichbar. |
| Fehlkäufe | Ungünstige Käufe belegen Platz oder verbrauchen einen benötigten Vorrat. | Pausieren, Rückbau oder Wiederherstellung erlauben die Fortsetzung ohne Neustart. |
| Speicherung | Ein Spielstand wird gespeichert, geschlossen und geladen. | Ressourcen, Gebäude und laufende Zustände bleiben konsistent. |
| Online und offline | Zwei identische Ausgangsstände laufen ohne Eingriffe jeweils zehn Minuten, einmal online und einmal offline. | Beide erreichen dieselben wirtschaftlichen Ergebnisse. Volle Lager und verbrauchte Inputs werden dabei berücksichtigt. |
| Entladene Bereiche | Die Startregion wird während laufender Produktion verlassen und später wieder betreten. | Der wirtschaftliche Fortschritt folgt den festgelegten Regeln; die geladene Darstellung zeigt den erreichten Zustand. |
| Aufenthalt in der Welt | Eine Testperson spielt einen vollständigen Durchlauf. | Beobachtung und Rückfrage klären, ob sie freiwillig durch die Siedlung geht, um Veränderungen zu sehen. |

Die Nachberechnung benötigt mindestens einen Vergleich mit Lagerfüllung oder Inputmangel während der Abwesenheit. Ein Test, in dem alle Raten durchgehend konstant bleiben, belegt diesen Teil des Modells nicht.

Wenn Testpersonen überwiegend die Oberfläche betrachten und Arbeitsorte nicht aufsuchen, bleibt die Sichtbarkeit eine offene Designaufgabe. Dann werden Darstellung und Gebäudeinteraktion überarbeitet, bevor weitere Produktionszweige hinzukommen.

## 3. Ursprünglichen v0.1-Umfang bis Eisen prüfen

Der im Konzept vorgesehene Produktionskern umfasst Holzfällerlager, Steinbruch, Sägewerk, Köhlerei, Erzmine und Schmelze. Hinzu kommen der Weltenkern als passive Startquelle sowie die Lagerfunktion. Die [ursprünglichen Kosten und Raten](game-design-v0.1.md) bleiben als Ausgangswerte erhalten.

Nach der empfohlenen Holz/Bretter-Vorstufe erweitert dieser Schritt die Wirtschaft um Stein, Kohle, Eisenerz und Eisen. Die Schmelze benötigt gleichzeitig Erz und Kohle. Dadurch wird erstmals geprüft, ob zwei getrennte Zulieferungen einen gemeinsamen Verbraucher verständlich versorgen.

Vor der Umsetzung der Eisenkette werden die Bauplatzregeln aufgelöst: Vier Bauplätze pro Region reichen für die genannten Gebäude ohne zusätzliche Regel oder rechtzeitige Erweiterung nicht aus. Bauvoraussetzungen und laufende Materialflüsse erhalten getrennte Darstellungen. Die Bretterkosten einer Erzmine bedeuten beispielsweise keinen laufenden Bretterverbrauch.

Geplante Prüfungen:

- [ ] Die automatische Eisenschmelze ist ohne manuelles Farmen erreichbar.
- [ ] Fehlendes Erz, fehlende Kohle und begrenzte Schmelzkapazität sind einzeln erkennbar.
- [ ] Kaufvorschauen zeigen, wann zusätzliche Förderung den Eisenfluss nicht erhöht und stattdessen Rohstoffe im Lager ansammelt.
- [ ] Lagergrenzen und konkurrierender Holzverbrauch führen zu erklärbaren Zuständen.
- [ ] Neue Betriebe und weitere Ausbauten bleiben nach dem Neustart korrekt; Online- und Offline-Fortschritt stimmen auch für die zusammenlaufenden Inputs überein.
- [ ] Die tatsächliche Zeit bis zur Eisenproduktion und die Wartephasen werden gemessen und mit den Designzielen verglichen.

Die 15 Minuten aus v0.1 sind auch hier ein Testziel. Die Platzhalterwerte belegen diese Taktung noch nicht; die Nachrechnung und ihre Annahmen stehen im [Review](idle-core-review.md).

## 4. Spätere Entwicklungsstufen

Konzept v0.1 ordnet den langfristigen Fortschritt von den Anfängen über Industrialisierung und Technologie zum Nether und End. Vor der Umsetzung dieser späteren Systeme stehen die folgenden Fragen an:

| Thema | Vor einer Umsetzung zu klären |
| --- | --- |
| Weitere Regionen und Biome | Unterschiede zwischen Flächen, verlässliche Startressourcen in generierten Regionen und Erweiterung bestehender Welten. |
| Größere Produktionsnetze | Nutzen von Nachbarschaften, tatsächlichen Transporten und gespeicherten Ressourcenreserven. |
| Automatische Bauaufträge | Welche Käufe der Spieler vorab freigibt und wie Prioritäten oder Ressourcenmangel wirken. |
| Forschung, Redstone und Energie | Welche zusätzlichen Entscheidungen die neuen Systeme ermöglichen. |
| Nether und End | Verbindung der Wirtschaft über Dimensionen und Aufwand der Darstellung. |
| Prestige | Unterschiedliche neue Aufbauwege, dauerhafte Freischaltungen und das Aussehen erhaltener Strukturen nach einem Level-Reset. |
| Multiplayer | Zuständigkeit für Wirtschaft und Spielstände; das Konzept sieht zunächst Singleplayer vor. |

Für diese Stufen gibt es weder feste Termine noch bestätigte Produktionswerte. Der [Entscheidungsstand](decisions.md) hält die offenen Fragen fest; der [Projekteinstieg](../README.md) führt zu Konzept und Review.
