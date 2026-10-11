# ADR 0002: Ressourcen-, Zeit- und Bauplatzvertrag für M0

Stand: 11. Oktober 2026

Status: Angenommen. Entscheidet [Issue #3](https://github.com/tizian-bitschi/idle-realms/issues/3) und die Fragen [O-02](../decisions.md) bis [O-09](../decisions.md). Zahlen, die als Testwert gekennzeichnet sind, werden in den ersten Playtests geprüft und können dann geändert werden. Die übrigen Regeln sind festgelegt.

Der Vertrag beschreibt den Prototyp M0 aus dem [Prototypplan](../prototype-plan.md), Abschnitt 2 (Holz und Bretter). Domänenkern, Persistenz, Darstellung und Tests greifen auf dieselben Regeln zu. Die Eisenkette bleibt außerhalb dieses Vertrags.

Die Referenzfälle sind ausführbar: `node tools/m0-contract/check.mjs` prüft RF-1 bis RF-10 ohne Abhängigkeiten.

## 1. Entscheidung

| Bereich | Festlegung |
| --- | --- |
| Umfang | Holz/Bretter-Vorstufe (O-02, Empfehlung E-06). Die Eisenkette folgt danach. |
| Ressourcen | Holz und Bretter als virtuelle Vorräte. Mengen sind ganze Zahlen in Untereinheiten. |
| Startbetrieb | Ein Forstbetrieb Stufe 1 auf Bauplatz P1, produziert ab Schritt 0. Ein Weltenkern erzeugt kein zusätzliches Holz. |
| Schritt | 1.000 ms. Die Wirtschaft rechnet nur vollständige Schritte. |
| Zeit | Systemuhr des Servers. Abwesenheit wird höchstens 8 Stunden angerechnet. |
| Raum | Startregion aus 2 × 2 Chunks mit vier Bauplätzen. Die erste Erweiterung fügt zwei Chunks und einen Bauplatz hinzu. |
| Belegung | Höchstens 3 Forstbetriebe und 2 Sägewerke, insgesamt höchstens 5 Betriebe. Bauplatz P2 ist für Sägewerke reserviert. |
| Arbeiter | Je Arbeiterslot ein Kupfergolem, fest zugeordnet zu seinem Betrieb. |

## 2. Einheiten und Vorräte

- 1 Einheit (E) = 1.000 Untereinheiten (U). Alle Mengen sind ganze Zahlen in U.
- Raten werden in U/s angegeben. Ein Schritt dauert 1 s, daher entspricht die Menge je Schritt der Rate.
- Lagerkapazität gilt je Ressource für die gesamte Welt (O-08). Ein Betrieb besitzt keinen eigenen Vorrat.
- Startzustand: Holz 0 U, Bretter 0 U, Holzkapazität 1.200 E, Brettkapazität 1.200 E. Alle Werte sind Testwerte.
- Ein Vorrat wird nie über seine Kapazität erhöht und nie negativ.
- Mengen und Zeitpunkte sind vorzeichenbehaftete 64-Bit-Ganzzahlen (`long`). Jede Kapazität ist auf 1.000.000.000 U begrenzt. Ein Ergebnis außerhalb dieser Grenzen gilt als Fehler: Der Schritt wird verworfen, der Zustand bleibt unverändert und der Fehler wird protokolliert. In M0 wird diese Grenze nicht erreicht (größte Kapazität 2.400.000 U, größte Rate 8.000 U/s).

## 3. Betriebe, Stufen und Kosten

Alle Zahlen in diesem Abschnitt sind Testwerte.

| Betrieb | Stufe | Eingang | Ausgang | Arbeiterslots |
| --- | --- | --- | --- | --- |
| Forstbetrieb | 1 | keiner | Holz 2.000 U/s | 1 |
| Forstbetrieb | 2 | keiner | Holz 3.000 U/s | 2 |
| Sägewerk | 1 | Holz 1.000 U/s | Bretter 4.000 U/s | 1 |
| Sägewerk | 2 | Holz 2.000 U/s | Bretter 8.000 U/s | 2 |

Beim Sägewerk gilt ein festes Verhältnis von 1 Holz zu 4 Brettern. Die Ausgangsrate ist immer das Vierfache der Eingangsrate.

| Vorgang | Kosten | Wirkung |
| --- | --- | --- |
| Forstbetrieb bauen (Stufe 1) | 40 Holz | Betrieb entsteht mit einem Golem |
| Sägewerk bauen (Stufe 1) | 60 Holz | Betrieb entsteht mit einem Golem |
| Forstbetrieb auf Stufe 2 | 60 Holz | Ertrag 3 Holz/s, zweiter Arbeiterslot |
| Sägewerk auf Stufe 2 | 80 Holz und 20 Bretter | Verarbeitung 2 Holz/s, zweiter Arbeiterslot |
| Holzlager ausbauen | 40 Holz | Holzkapazität +1.200 E, höchstens einmal |
| Brettlager ausbauen | 60 Holz | Brettkapazität +1.200 E, höchstens einmal |
| Erste Erweiterung E1 | 120 Holz und 40 Bretter | Bauplatz E1 wird nutzbar, höchstens einmal |

Ein Vorgang wird nur ausgeführt, wenn der gesamte Betrag im Vorrat zu Schrittbeginn vorhanden ist. Teilzahlungen gibt es nicht. Kosten werden im Schritt der Validierung abgebucht (Abschnitt 6).

## 4. Zeit

### 4.1 Uhr, Anker und Schritte

- Die Wirtschaft liest die Systemuhr des Servers in Millisekunden (UTC). Die Epoche E ist der Zeitpunkt des Befehls `/idlerealms prototyp start`. E muss nicht auf einer vollen Sekunde liegen.
- Der Zeitanker ist der Beginn des nächsten nicht berechneten Schritts. Er wird gespeichert.
- Der Beginn des Schritts Nummer k liegt bei E + k × 1.000 ms. Er wird berechnet, sobald die Systemuhr mindestens 1.000 ms über seinem Beginn liegt.
- Angefangene Schritte werden nicht gespeichert. Die Restzeit (weniger als 1.000 ms) bleibt beim Anker und wird im nächsten vollständigen Schritt verwendet. Eine Zerlegung der Zeit ändert das Ergebnis deshalb nicht (RF-6).

### 4.2 Spielpause, geschlossene Sitzung, entladene Chunks

- Die Wirtschaft läuft nach der Systemuhr weiter, auch während einer Spielpause, bei geschlossener Sitzung und bei entladenen Chunks.
- Beim Zurückkehren wird bis zur aktuellen Systemuhr nachberechnet. Die Darstellung zeigt danach den tatsächlichen Betriebszustand.
- Entladene Chunks ändern weder Produktion noch Vorräte. Die Darstellung wird beim Laden aus dem gespeicherten Zustand aufgebaut.

### 4.3 Obergrenze der Abwesenheit

- Angerechnet werden höchstens 8 Stunden (28.800.000 ms, also 28.800 Schritte).
- Liegt der Anker mehr als 8 Stunden vor der Systemuhr, rückt er auf den Beginn des Schritts, der 8 Stunden vor der Systemuhr liegt. Der ältere Zeitraum wird verworfen.

### 4.4 Negative Zeitdifferenz

- Liegt die Systemuhr vor dem Anker, wird kein Schritt berechnet. Der Anker wird nicht zurückgesetzt.
- Die Wirtschaft wartet, bis die Systemuhr den Anker wieder erreicht. Eine Rückstellung erzeugt dadurch keine zusätzlichen Erträge. Dauert die Rückstellung länger als 8 Stunden, bleibt die Wirtschaft entsprechend lange ohne Fortschritt.

### 4.5 Zeitpunkt von Befehlen

- Ein Befehl mit Systemzeit t wirkt am Schrittbeginn b = E + ⌈(t − E) / 1.000 ms⌉ × 1.000 ms. Das ist der erste Schritt, dessen Beginn nicht vor t liegt.
- Alle Zeitangaben der Referenzfälle sind Differenzen zur Epoche. RF-10 prüft eine Epoche von 250 ms.
- Ein Befehl mit b vor dem Anker wirkt am Anker.
- Ausstehende Befehle werden mit ihrem Zeitpunkt und einer Sequenznummer gespeichert. Die Sequenznummer ist die Reihenfolge des Eingangs auf dem Server.
- Befehle mit demselben b werden in Sequenzreihenfolge angewandt.

## 5. Schrittberechnung

### 5.1 Reihenfolge je Schritt

Für den Schritt mit Beginn b gilt diese Reihenfolge:

1. Befehle mit Zeitpunkt b in Sequenzreihenfolge anwenden. Ein abgelehnter Befehl (Kosten, Bauplatz, Maximalzahl) ändert nichts und wird gemeldet.
2. Ausstehende Stufenwechsel anwenden, deren Wirksamkeit bei b oder früher liegt.
3. Eingangspool je Ressource: Vorrat zu Schrittbeginn. Freier Ausgang je Ressource: Kapazität minus Vorrat zu Schrittbeginn.
4. Betriebe in aufsteigender ID-Reihenfolge verarbeiten. Die ID ist die Erstellungsreihenfolge und bleibt stabil.
   - Jeder Betrieb liest die Restwerte, die nach den Entnahmen der vorherigen Betriebe übrig sind. Status und Verbrauch dieses Betriebs entstehen ausschließlich aus diesen Restwerten.
   - Forstbetrieb: erzeugt min(Ausgangsrate, freier Restausgang).
   - Sägewerk: verbraucht c = min(Eingangsrate, Resteingang, ⌊freier Restausgang / 4⌋) und erzeugt 4 × c.
   - Was ein Betrieb entnimmt, steht den folgenden Betrieben nicht mehr zur Verfügung.
5. Vorrat = Vorrat zu Schrittbeginn + Erträge − Verbräuche. Im Schritt erzeugtes Material und frei gewordener Lagerplatz sind erst im nächsten Schritt nutzbar.

Ein Sägewerk, dessen freier Ausgang nicht für ein ganzes Verhältnis reicht, verbraucht weniger Holz. Der restliche Holzvorrat bleibt im Lager und wird später verarbeitet. Dadurch entstehen keine Produktionsreste, die verloren gehen.

### 5.2 Anlauf, Stufen und Pause

- Ein gebauter Betrieb produziert ab dem Schritt b + 1.000 ms. Im Schritt bei b erzeugt er nichts. Diese Anlaufverzögerung beträgt 1 Schritt.
- Ein Stufenwechsel wirkt ab b + 1.000 ms. Bis dahin läuft die alte Stufe weiter; ein Upgrade unterbricht die Produktion nicht.
- Eine Betriebspause wirkt im Schritt bei b. Ein Fortsetzen wirkt ebenfalls im Schritt bei b, ohne Anlauf.
- Ein Lagerausbau wirkt im Schritt bei b, weil er keine Produktionsanlage ist. Eine neue Erweiterung E1 ist ab b verfügbar.
- Ein neuer Betrieb erhält vor seiner Wirksamkeit keinen Ertrag.

### 5.3 Status, Nennleistung, ausführbare Rate und Nettorate

Jeder Betrieb hat in jedem Schritt genau einen Status. Mehrere zutreffende Bedingungen werden nach dieser Priorität entschieden:

| Priorität | Status | Bedingung im Schritt |
| --- | --- | --- |
| 1 | PAUSED | Betriebspause wirksam |
| 2 | OUTPUT_FULL | Restausgang des Betriebs lässt keine Ausgabeeinheit zu (0 U bei Forstbetrieb, weniger als 4 U bei Sägewerk) |
| 3 | MISSING_INPUT | Resteingang des Betriebs ist 0 U |
| 4 | RUNNING | sonst; ausführbare Rate größer als 0 |

Der Anlauf (Abschnitt 5.2) hat Vorrang vor den Stufen 2 bis 4. Ein Betrieb im Anlauf zeigt den Status RUNNING mit der Phase Anlauf und der ausführbaren Rate 0, auch wenn ihm Eingang oder Ausgang fehlen. Anlauf ist keine eigene Statusstufe. PAUSED hat auch im Anlauf Vorrang.

Drei Größen werden getrennt geführt:

- **Nennleistung:** die Raten der aktuellen Stufe aus Abschnitt 3.
- **Ausführbare Rate:** die Mengen, die der Betrieb im letzten berechneten Schritt tatsächlich erzeugt und verbraucht hat. Sie kann unter der Nennleistung liegen.
- **Nettorate:** je Ressource die ausführbare Ausgangsrate minus die ausführbare Eingangsrate. Ein laufendes Sägewerk hat zum Beispiel die Nettorate Holz −1.000 U/s und Bretter +4.000 U/s.

## 6. Befehle

| Befehl | Voraussetzung | Wirkung im Schritt bei b |
| --- | --- | --- |
| Bau | Bauplatz frei und zugelassen, Maximalzahl nicht erreicht, Bauzone frei (Abschnitt 7.2), Kosten vorhanden | Betrieb entsteht; Kosten werden abgebucht; Produktion ab b + 1.000 ms |
| Upgrade | Stufe 1, kein ausstehendes Upgrade, Kosten vorhanden. Die Bauzone ist bereits für alle Stufen reserviert, daher ist keine weitere Prüfung nötig. | Kosten werden abgebucht; Stufe 2 ab b + 1.000 ms |
| Betriebspause | Betrieb vorhanden | kein Ertrag und kein Verbrauch ab b; kostenlos |
| Fortsetzen | Betrieb pausiert | Produktion ab b; kostenlos |
| Versetzen | Ziel-Bauplatz frei und zugelassen, Bauzone am Ziel frei (Abschnitt 7.2) | Betrieb wechselt den Bauplatz; ID, Stufe und Pausenzustand bleiben erhalten; Anlauf 1 Schritt am Ziel; kostenlos und verlustfrei |
| Lagerausbau | siehe Tabelle in Abschnitt 3, Kosten vorhanden | Kapazität steigt ab b |
| Erste Erweiterung E1 | nur einmal, Kosten vorhanden | Bauplatz E1 ist ab b nutzbar |

- Bau, Upgrade und Versetzen prüfen die Bauzone des Zielbauplatzes. Bestehende Spielerblöcke in der Bauzone verhindern die Aktion. Die Mod überschreibt oder entfernt keine Blöcke.
- M0 enthält keinen Rückbau. Fehlkäufe werden durch Betriebspause und Versetzen korrigiert.
- Der Startbetrieb kann nicht entfernt werden. Er kann versetzt werden und lässt sich kostenlos zurück auf P1 versetzen, sobald P1 frei ist.

## 7. Raum

### 7.1 Koordinaten und Bauplätze

Koordinaten sind Blöcke relativ zur Ecke des Chunks, in dem der Befehl `/idlerealms prototyp start` ausgeführt wird. Der Bodenhorizont aller Bauplätze liegt auf der Höhe dieser Position.

| Bereich | x | z | Verwendung |
| --- | --- | --- | --- |
| Startregion | 0–31 | 0–31 | 2 × 2 Chunks, erschlossen |
| P1 (Nordwest) | 2–13 | 2–13 | Startforstbetrieb |
| P2 (Nordost) | 18–29 | 2–13 | nur Sägewerk, reserviert |
| P3 (Südwest) | 2–13 | 18–29 | frei |
| P4 (Südost) | 18–29 | 18–29 | frei |
| E1 | 34–45 | 2–13 | nach der Erweiterung nutzbar |
| Erweiterungs-Chunks | 32–63 | 0–31 | zusammen mit E1 erschlossen |

Der Startbefehl prüft vor dem Anlegen, dass die Bauzonen P1 bis P4 frei sind. Ist eine Bauzone belegt, wird der Befehl mit der Liste der belegten Positionen abgewiesen. Die Welt bleibt dann unverändert.

### 7.2 Grundrisse und Laufwege

- Die Bauzone eines Bauplatzes umfasst 8 × 8 Blöcke innerhalb des Bauplatzes (lokale Koordinaten 2–9) sowie 8 Blöcke in der Höhe über dem Bodenhorizont.
- Ein Betrieb der Stufe 1 belegt 6 × 6 Blöcke, zentriert in der Bauzone (lokale Koordinaten 3–8). Die Stufe 2 belegt die gesamte Bauzone mit 8 × 8 Blöcken.
- Laufwege: 2 Blöcke am Rand des Bauplatzes, 4 Blöcke zwischen zwei Bauplätzen (P1/P2, P1/P3, P2/P4, P3/P4 und P2/E1), 2 Blöcke an der Kante der Startregion.
- Außerhalb der Bauplätze kann nichts gebaut werden. Freie Bewegung ist im gesamten Gebiet möglich; wirtschaftlich genutzt werden nur erschlossene Bauplätze.

### 7.3 Erschließung

- Die Startregion ist bei Beginn vollständig erschlossen (E-04).
- E1 wird erst durch die Erweiterung erschlossen. Vorher ist das Gebiet begehbar, aber nicht bebaubar (E-05).
- Erschließung begrenzt Bauen und wirtschaftliche Nutzung, nicht die Bewegung (O-04).

## 8. Belegung

- Forstbetriebe: höchstens 3. Sägewerke: höchstens 2. Insgesamt höchstens 5 Betriebe. Das entspricht den 5 Bauplätzen, weil jeder Bauplatz höchstens einen Betrieb aufnimmt.
- P2 nimmt ausschließlich ein Sägewerk auf. Dadurch bleibt die Sägewerkskette immer baubar.
- Erreichbarkeit: Holz stammt vom Startforstbetrieb, der ohne Eingang produziert. Bretter stammen vom Sägewerk auf P2. Die Erweiterung benötigt nur diese beiden Ressourcen. Ein ungünstiger Kauf verringert den Holzvorrat nur vorübergehend. Pausiert der Spieler den Startforstbetrieb selbst, kann er ihn jederzeit fortsetzen.
- Der dritte Forstbetrieb braucht den fünften Bauplatz E1. Holz ist der Engpass für Erweiterung und Verarbeitung; ein zusätzlicher Forstbetrieb erhöht ihn direkt.
- Der früheste Weg zur ersten Erweiterung nutzt drei Käufe: zweiten Forstbetrieb bei 20.000 ms, Sägewerk bei 36.000 ms, Erweiterung bei 75.000 ms (RF-9). Unter allen Reihenfolgen der drei Käufe, jeweils zum frühestmöglichen Schritt, ist dies der früheste Zeitpunkt. Ohne zweiten Forstbetrieb liegt die Erweiterung bei 149.000 ms.

## 9. Darstellung

- **Golemtyp:** Kupfergolem mit dem Vanilla-Modell der Java Edition 26.1.2 (O-05). Werkzeuge werden als Anbauteile dargestellt (Axt am Forstbetrieb, Säge am Sägewerk). Eigene Assets sind für M0 nicht vorausgesetzt.
- **Zuordnung:** Jeder Arbeiterslot hat einen festen Golem. Bau und Stufe 2 bringen den Golem mit. Ein Golem wechselt den Betrieb nicht (E-02).
- **Zustandsdarstellung:**
  - RUNNING: Golem arbeitet am Arbeitsplatz; Ein- und Ausgabe sind sichtbar.
  - Anlauf: Golem geht zum Arbeitsplatz; keine Ausgabe.
  - MISSING_INPUT: Golem wartet am leeren Eingang.
  - OUTPUT_FULL: Golem wartet; der Ausgabestapel ist voll.
  - PAUSED: Golem steht am Arbeitsplatz mit Stillstandsmarkierung.
- **Lagerfüllstände:** Fünf Stufen sind unterscheidbar: leer, bis zu einem Drittel, bis zu zwei Dritteln, unter voll, voll. Die exakte Menge steht in der Oberfläche (Testwert für die Grenzen).
- **Spielerabbau:** Darstellungsblöcke eines Betriebs sind nicht abbaubar. Ein Abbauversuch wird abgewiesen. Frei abbaubare Gestaltungsblöcke sind getrennt; ihr Abbau erzeugt keinen Vorrat (E-10).
- **Explosionen:** Darstellungsblöcke sind explosionsfest. Ein Schaden an ihnen ändert weder Vorrat noch Betriebszustand.
- **Extern entfernte Darstellungsblöcke** (etwa durch einen Befehl oder einen anderen Mod): Die Wirtschaft bleibt unverändert, es gibt keine Gutschrift. Die Darstellung wird beim nächsten Laden des Chunks wiederhergestellt.
- **Vorhandene Spielerblöcke in der Bauzone:** Der Bau wird abgewiesen, die Rückmeldung nennt die Zahl blockierter Positionen. Die Mod entfernt keine Spielerblöcke.

## 10. Persistenz

Gespeichert werden (SavedData nach [ADR 0001](0001-platform.md)):

- Epoche und Zeitanker;
- Vorräte und Kapazitäten je Ressource;
- freigeschaltete Bauplätze;
- Betriebe mit ID, Typ, Bauplatz, Stufe, ausstehender Stufe und deren Wirksamkeit, Pausenzustand und Wirksamkeit des Anlaufs;
- letzter Status, Phase und ausführbare Rate je Betrieb. Die Darstellung zeigt damit nach dem Laden bis zum nächsten berechneten Schritt denselben Zustand wie vor dem Speichern;
- nächste ID;
- ausstehende Befehle mit Zeitpunkt und Sequenznummer.

Restzeiten und halb berechnete Schritte werden nicht gespeichert.

## 11. Referenzfälle

Die Referenzfälle sind ausführbar: `node tools/m0-contract/check.mjs` prüft RF-1 bis RF-10. Das Skript ist die Referenzfassung der Regeln aus den Abschnitten 4 bis 8; die Tabellen geben seine Ergebnisse wieder. Die Tabellen zeigen den Zustand nach Abschluss des Schritts bei b. Alle Mengen stehen in U.

### RF-1: Sägewerk ohne Holzvorrat

Ausgangszustand: Sägewerk (Stufe 1) auf P2 aktiv, Holz 0 U, Bretter 0 U. Der Forstbetrieb auf P1 ist pausiert und wird bei Systemzeit 3.000 ms fortgesetzt (wirkt bei b = 3.000 ms).

| b (ms) | Befehl | Sägewerk | Forstbetrieb | Holz (U) | Bretter (U) |
| --- | --- | --- | --- | ---: | ---: |
| 0 | – | MISSING_INPUT | PAUSED | 0 | 0 |
| 1.000 | – | MISSING_INPUT | PAUSED | 0 | 0 |
| 2.000 | – | MISSING_INPUT | PAUSED | 0 | 0 |
| 3.000 | Fortsetzen | MISSING_INPUT | RUNNING | 2.000 | 0 |
| 4.000 | – | RUNNING (1.000 U Holz, 4.000 U Bretter) | RUNNING | 3.000 | 4.000 |

Im Schritt bei 3.000 ms sieht das Sägewerk den Holzvorrat zu Schrittbeginn (0 U). Das Holz des Forstbetriebs ist erst im nächsten Schritt verwendbar.

### RF-2: Volles Ausgabelager

Ausgangszustand: Sägewerk aktiv, Holz 5.000 U, Brettlager voll (1.200.000 U).

| b (ms) | Befehl | Sägewerk | Holz (U) | Bretter (U) |
| --- | --- | --- | ---: | ---: |
| 0 | – | OUTPUT_FULL | 5.000 | 1.200.000 |
| 1.000 | – | OUTPUT_FULL | 5.000 | 1.200.000 |
| 2.000 | – | OUTPUT_FULL | 5.000 | 1.200.000 |

Das Sägewerk verbraucht kein Holz, solange kein Ausgabeplatz frei ist.

### RF-3: Pause und Fortsetzen

Ausgangszustand: Sägewerk aktiv, Holz 10.000 U, Bretter 0 U. Die Betriebspause wird bei Systemzeit 1.500 ms angefordert (wirkt bei b = 2.000 ms), das Fortsetzen bei 4.200 ms (wirkt bei b = 5.000 ms).

| b (ms) | Befehl | Sägewerk | Holz (U) | Bretter (U) |
| --- | --- | --- | ---: | ---: |
| 0 | – | RUNNING | 9.000 | 4.000 |
| 1.000 | – | RUNNING | 8.000 | 8.000 |
| 2.000 | Betriebspause | PAUSED | 8.000 | 8.000 |
| 3.000 | – | PAUSED | 8.000 | 8.000 |
| 4.000 | – | PAUSED | 8.000 | 8.000 |
| 5.000 | Fortsetzen | RUNNING | 7.000 | 12.000 |

### RF-4: Zwei konkurrierende Verbraucher

Ausgangszustand: Sägewerk A (P2, ID 1) und Sägewerk B (P4, ID 2) aktiv, Holz 1.500 U, Bretter 0 U.

| b (ms) | Befehl | Sägewerk A | Sägewerk B | Holz (U) | Bretter (U) |
| --- | --- | --- | --- | ---: | ---: |
| 0 | – | RUNNING (1.000 U Holz) | RUNNING (500 U Holz) | 0 | 6.000 |
| 1.000 | – | MISSING_INPUT | MISSING_INPUT | 0 | 6.000 |

Der Eingangspool reicht für Betrieb A vollständig. Betrieb B erhält den Rest und verarbeitet nur 500 U. Die Reihenfolge folgt der ID.

### RF-5: Zwei Produzenten an einem fast vollen Holzlager

Ausgangszustand: Forstbetrieb A (P1, ID 1) und Forstbetrieb B (P3, ID 2) aktiv, Holz 1.199.000 U (Kapazität 1.200.000 U, also 1.000 U frei).

| b (ms) | Befehl | Forst A | Forst B | Holz (U) |
| --- | --- | --- | --- | ---: |
| 0 | – | RUNNING (1.000 U) | OUTPUT_FULL | 1.200.000 |
| 1.000 | – | OUTPUT_FULL | OUTPUT_FULL | 1.200.000 |

Betrieb A erhält den einzigen freien Platz, weil er zuerst verarbeitet wird. Betrieb B erzeugt nichts und wartet.

### RF-6: Zerlegung der Zeit

Ausgangszustand: Forstbetrieb aktiv, Holz 0 U. Berechnet werden 4.000 ms in einem Stück und in zwei Abschnitten (2.500 ms, danach 4.000 ms).

| Ergebnis | Schritte | Holz (U) |
| --- | ---: | ---: |
| Ein Abschnitt bis 4.000 ms | 4 | 8.000 |
| Zwei Abschnitte bis 2.500 ms und 4.000 ms | 4 | 8.000 |

Beide Varianten sind identisch. Der Rest von 500 ms bleibt beim Anker und wird im Schritt bei 2.000 ms vollständig verwendet. Das Skript prüft zusätzlich 200 Zerlegungen eines Zeitraums von 10 Minuten mit festem Zufallsstartwert; alle liefern identische Vorräte.

### RF-7: Pause, Upgrade und Bau im selben Teilintervall

Ausgangszustand: Forstbetrieb #1 (P1, Stufe 1) aktiv, Holz 500.000 U.

Befehle mit Systemzeit zwischen 2.000 ms und 3.000 ms wirken alle am Schrittbeginn 3.000 ms:

- 2.300 ms: Betriebspause für #1 (wirkt bei 3.000 ms);
- 2.700 ms: Upgrade von #1 auf Stufe 2 (Kosten 60 Holz, Wirkung ab 4.000 ms);
- 2.900 ms: Bau eines Forstbetriebs #2 auf P3 (Kosten 40 Holz).

Fortsetzen von #1 bei Systemzeit 5.000 ms.

| b (ms) | Befehl | Forst #1 | Forst #2 | Holz (U) |
| --- | --- | --- | --- | ---: |
| 2.000 | – | RUNNING (2.000 U) | – | 506.000 |
| 3.000 | Pause #1, Upgrade #1, Bau #2 | PAUSED | Anlauf | 406.000 |
| 4.000 | – | PAUSED | RUNNING (2.000 U) | 408.000 |
| 5.000 | Fortsetzen #1 | RUNNING (3.000 U, Stufe 2) | RUNNING (2.000 U) | 413.000 |
| 6.000 | – | RUNNING (3.000 U) | RUNNING (2.000 U) | 418.000 |

Die Kosten beider Vorgänge werden bei 3.000 ms abgebucht (100.000 U), bevor die Produktion dieses Schritts berechnet wird. Die Pause wirkt bereits in diesem Schritt. Die Stufe 2 ist ab 4.000 ms gesetzt, produziert aber erst nach dem Fortsetzen.

### RF-8: Anlauf nach einem Bau

Ausgangszustand: Forstbetrieb #1 aktiv, Holz 0 U. Der Bau eines Forstbetriebs #2 erfolgt bei b = 50.000 ms.

| b (ms) | Befehl | Forst #1 | Forst #2 | Holz (U) |
| --- | --- | --- | --- | ---: |
| 49.000 | – | RUNNING (2.000 U) | – | 100.000 |
| 50.000 | Bau #2 | RUNNING (2.000 U) | Anlauf | 62.000 |
| 51.000 | – | RUNNING (2.000 U) | RUNNING (2.000 U) | 66.000 |
| 52.000 | – | RUNNING (2.000 U) | RUNNING (2.000 U) | 70.000 |

Der neue Betrieb erzeugt im Schritt bei 50.000 ms nichts. Erst im Schritt bei 51.000 ms erhöht er den Vorrat.

### RF-9: Frühester Weg zur ersten Erweiterung

Ausgangszustand: Forstbetrieb #1 aktiv, Holz 0 U, Bretter 0 U. Drei Käufe: zweiter Forstbetrieb (40 Holz), Sägewerk (60 Holz), Erweiterung E1 (120 Holz, 40 Bretter). Jeder Kauf erfolgt zum frühestmöglichen Schritt.

| Zeitpunkt (b) | Ereignis |
| --- | --- |
| 20.000 ms | Zweiter Forstbetrieb auf P3 (40 Holz) |
| 36.000 ms | Sägewerk auf P2 (60 Holz) |
| 75.000 ms | Erste Erweiterung E1 (120 Holz, 40 Bretter) |

Die Erweiterung braucht Bretter, daher kann sie nicht vor dem Sägewerk liegen. Die übrigen Reihenfolgen liefern: Sägewerk, zweiter Forstbetrieb, Erweiterung bei 110.000 ms; Sägewerk, Erweiterung, zweiter Forstbetrieb bei 149.000 ms. Die frühestmögliche Erweiterung liegt bei 75.000 ms.

### RF-10: Epoche nicht auf der Sekunde

Ausgangszustand: Epoche 250 ms, Forstbetrieb #1 aktiv, Holz 0 U. Betriebspause bei Systemzeit 1.501 ms (wirkt bei b = 2.250 ms).

| b (ms) | Befehl | Forst #1 | Holz (U) |
| --- | --- | --- | ---: |
| 250 | – | RUNNING (2.000 U) | 2.000 |
| 1.250 | – | RUNNING (2.000 U) | 4.000 |
| 2.250 | Betriebspause | PAUSED | 4.000 |
| 3.250 | – | PAUSED | 4.000 |

Die Schrittgrenzen liegen bei 250, 1.250, 2.250 und 3.250 ms. Der Befehl bei 1.501 ms wirkt bei 2.250 ms.

## 12. Laufzeitgrenzen

| Grenze | Wert | Status |
| --- | --- | --- |
| Betriebe in M0 | höchstens 5 (3 Forst-, 2 Sägewerkbetriebe) | Festlegung |
| Maximale Nachberechnung | 28.800 Schritte (8 Stunden) | Festlegung |
| Nachberechnungsbudget | höchstens 100 ms für 28.800 Schritte mit 5 Betrieben | Testwert, bei der Implementierung zu messen |
| Referenzumgebung | Windows 11 Pro, 64-Bit-JDK 25, Einzelspielerwelt auf dem lokalen Datenträger des Entwicklungsrechners | Festlegung; die konkrete Hardware wird bei der Messung dokumentiert |

Eine JavaScript-Fassung derselben Regeln brauchte für denselben Zeitraum auf dem Entwicklungsrechner etwa 23 ms. Das ist kein Messwert für die Mod, sondern nur ein Hinweis, dass die Grenze nicht von vornherein unrealistisch ist.

## 13. Bezug zu anderen Unterlagen

- **Architektur:** Die Schrittlogik aus Abschnitt 5 liegt im Domänenkern ohne Minecraft-Abhängigkeit. Der Adapter übersetzt Befehle und Zustand ([ADR 0001](0001-platform.md)).
- **Entscheidungsstand:** O-02 bis O-09 sind mit Ergebnis und Datum im [Entscheidungsstand](../decisions.md) festgehalten. Dort stehen nur die Entscheidungen; die Zahlen stehen in diesem ADR.
- **Offene Testwerte:** Kosten, Kapazitäten, Raten, die Schwelle der Lagerfüllstände und das Nachberechnungsbudget. Sie werden in den ersten Playtests und bei der Messung geprüft.
- **Review:** Die Empfehlungen E-02 bis E-05 sind in den Abschnitten 1, 7, 8 und 9 umgesetzt. E-03 bestimmt, dass nur der gespeicherte Betriebszustand Erträge erzeugt.
