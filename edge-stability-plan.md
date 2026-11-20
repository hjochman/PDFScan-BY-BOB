# Plan: Stabilere Kantenerkennung (Live-Overlay und Auto-Capture)

## Überblick

**Ziel:** Der Rahmen in der Scanansicht soll ruhig auf dem Blatt stehen. Er soll nicht zwischen Kandidaten springen, nicht zittern und nicht zwischendurch verschwinden. Auto-Capture soll bei ruhig gehaltenem Handy zuverlässig auslösen.

**Beobachtung (vom Nutzer bestätigt):** Der Rahmen springt zwischen Kandidaten und zittert. Auto-Capture löst wegen angeblicher Instabilität kaum aus.

**Ursachen laut Code:**

| Symptom | Ursache |
|---|---|
| Springen zwischen Kandidaten | `DocumentDetector.detectRobust` wertet pro Frame 5 Masken aus und nimmt nur den Gewinner nach `quadScore`. Es gibt kein Gedächtnis über Frames hinweg. |
| Eckenvertauschung | Zwei verschiedene Eckreihenfolgen: `DocumentGeometry.orderCorners` (sortiert nach y) und `DocumentDetector.cornersFromPoints` (Summe/Differenz). |
| Zittern | Ecken kommen aus `approxPolyDP` mit wechselndem Epsilon (0.015 bis 0.08), nicht subpixel-genau. `CornerSmoother` nutzt ein festes Alpha. |
| Auto-Capture löst nicht aus | Ein einziger Fallback-Frame setzt `CornerSmoother` und `AutoCaptureController` zurück (`ScanViewModel.onDetectionResult`). `maxDriftPx = 15f` ist absolut und nicht auf die Framegröße bezogen. |

**Vorgehen:** Vier Stufen. Jede Stufe ist unabhängig, wird auf dem Gerät mit der Messung aus Stufe 1 geprüft und erst nach Freigabe durch den Nutzer fortgesetzt. Die Logik wird, wo möglich, in reine Kotlin-Klassen ausgelagert, damit sie auf der JVM testbar ist (wie `CornerSmoother`, `AutoCaptureController`).

**Nicht im Umfang:** Neue Erkennungsalgorithmen (z. B. ML-Modelle), Änderungen an Crop, PDF-Erzeugung oder UI-Layout. Der Totcode `detectByEdges` (Stufe 1, wird nicht aufgerufen) wird nicht angefasst, außer er stört bei einer Stufe.

**Rahmenbedingungen aus `AGENT.md`:**
- Build/Test: `./gradlew test assembleDebug lint`. Subagents dürfen kein Gradle starten.
- Geräte-Debugging: `adb` unter `/opt/homebrew/bin/adb`, `adb devices -l` prüfen. Debug- und Release-APK sind verschieden signiert.
- Logs dürfen keine Pfade oder Dateinamen enthalten. Nur Zahlen und Kennungen loggen.
- Edits an derselben Datei nacheinander, nicht parallel.
- Bei Versionsänderung: `VERSION`, `CHANGELOG.md` (Abschnitt `[Unreleased]`), `versionCode`, APKs nach `dist/`.
- Neue Tests gehören nach `app/src/test/java/com/hape/pdfscan/scan/`.

---

## Sub-Task 1: Messung und Logging

**Status:** [x] done (Baseline gemessen am 2026-11-20, Samsung SM-G998B, Debug-Build). Wartet auf Freigabe des Nutzers für Sub-Task 2.

**Baseline (Messwerte aus Logcat `EdgeStats`, Fenster 30 Frames, Sprung = Drift > 15 px, Frames ca. 15 bis 20 pro Sekunde):**

| Szene | Frames | Sprünge pro 30 Frames (Mittel / Max) | Aussetzer pro 30 Frames | Mittlere Drift | Maximale Drift | Auto-Capture |
|---|---|---|---|---|---|---|
| A: einfarbiger Tisch, ruhig | 354 | 27,3 / 30 | 0 | 142 px | 606 px | nicht ausgelöst |
| B: gemusterter Untergrund, ruhig | 244 | 2,8 / 14 | 0 | 36 px | 382 px | ausgelöst |
| C: Handy langsam bewegt | 198 | 16,5 / 29 | 0 | 130 px | 557 px | nicht ausgelöst |

Gewinner-Masken (Anzahl Frames): A: 4=225, 1=62, 0=43, 3=24 · B: 2=127, 1=65, 3=42, 4=7, 0=3 · C: 1=47, 4=46, 3=40, 2=39, 0=26. Kandidaten pro Frame im Mittel ca. 21 bis 23.

Hinweise zur Auswertung:
- Der Gewinner wechselt in allen Szenen zwischen mehreren Masken. In A dominiert Maske 4 (dilatierte Kanten), in B Maske 2 (Otsu). Das passt zur Ursache „kein Gedächtnis über Frames hinweg“.
- Szene A (einfarbig, ruhig) ist trotz erwarteter Ruhe die instabilste mit 27 Sprüngen pro 30 Frames. Szene B war nach den ersten Sekunden ruhig (ab ca. 16:23:57 keine Sprünge, Drift unter 15 px) und hat ausgelöst. Szene B enthält also eine unruhige Anfangsphase (Maximum 14 Sprünge, 382 px) und eine stabile Endphase.
- In A, B und C gab es im jeweils ausgewerteten Zeitraum keinen Fallback-Frame, also keine Aussetzer. In A traten nach dem Wegnehmen des Handys (ca. 16:20:53 bis 16:20:56) 45 Fallback-Frames auf. Diese sind nicht in der Tabelle, da der Ausschnitt 16:20:30 bis 16:20:52 ausgewertet wurde.
- Sprünge stammen demnach vor allem aus dem Wechsel zwischen Kandidaten (Driftwerte von 300 bis 600 px), nicht aus Zittern (Drift unter 15 px zählt nicht). Das stützt Sub-Task 3 (Tracker mit Hysterese). Aussetzer sind in diesen Messungen kein Problem, Sub-Task 2 Punkt C (Aussetzer-Toleranz) ist damit laut Baseline weniger dringend.
- Grenzen der Messung: Handy frei in der Hand, Szenen nicht mit Stativ, Szene A enthält vermutlich Handbewegung. Zeitfenster der Szenen wurden anhand der Zeitstempel im Log abgegrenzt.

**Umsetzung:**
- `DetectionResult` hat neue Felder `winnerMask` (0 geschlossene Kanten, 1 geschlossene sensitive Kanten, 2 Otsu, 3 Otsu invers, 4 dilatierte Kanten; -1 = nichts gefunden), `winnerScore`, `candidateCount`. Die Erkennung selbst ist unverändert.
- Neue Klasse [`DetectionStats`](app/src/main/java/com/hape/pdfscan/scan/DetectionStats.kt) (Fenster 30 Frames, Sprungschwelle 15 px wie `maxDriftPx`). Drift wird nur zwischen zwei Nicht-Fallback-Frames gemessen. Test: `DetectionStatsTest` (11 Fälle).
- `ScanCameraController.analyzeFrame`: Log-Tag `EdgeStats` (nur `BuildConfig.DEBUG`, nur Zahlen) und erweiterte Diagnosezeile (`mask`, `cand`, `Spruenge`, `Aussetzer`, `Drift mittel/max`).
- Validierung: `./gradlew test assembleDebug lint` erfolgreich, keine neuen Lint-Befunde.
- Erster Gerätelauf (Blatt, Kamera frei in der Hand, keine Messszene): `mask` wechselt zwischen 1 und 4, `cand` 16 bis 18, Score 0,27 bis 0,40, 18 bis 25 Sprünge pro 30 Frames, mittlere Drift 226 bis 303 px, maximal 409 px, Analysezeit 50 bis 60 ms. Das ist nur ein Funktionsnachweis, keine Baseline.

**Intent:** Vor jeder Änderung eine Baseline schaffen, damit die Wirkung der Maßnahmen messbar ist und nicht nur gefühlt.

**Expected Outcomes:**
- Pro Frame ist per Logcat sichtbar: Gewinner-Maske, Score, Zahl der Kandidaten, Eckendrift zum Vorframe, Fallback ja/nein.
- Ein Zähler „Sprünge und Aussetzer pro 30 Frames“ steht in der Debug-Diagnosezeile und im Log.
- Baseline-Werte für 2 bis 3 feste Szenen sind notiert (siehe Messprotokoll unten).

**Todo List:**
1. Erkennung gibt zusätzlich zur Ecke Diagnosedaten zurück: Index/Name der Gewinner-Maske, Score, Kandidatenzahl. Entweder neue optionale Felder in `DetectionResult` oder ein getrenntes Diagnose-Objekt.
2. Reine Hilfsklasse (z. B. `DetectionStats`, JVM-testbar), die aus aufeinanderfolgenden Ergebnissen berechnet: Anzahl Sprünge (Drift über Schwelle), Anzahl Aussetzer (Fallback), maximale und mittlere Eckendrift.
3. In `ScanCameraController.analyzeFrame` die Daten in Logcat schreiben (Tag z. B. `EdgeStats`, nur Zahlen, nur in Debug-Builds) und in die vorhandene Diagnosezeile einbinden (`ScanViewModel.onDiagnostics`, wird nur bei `isDebuggable` angezeigt).
4. Unit-Test für `DetectionStats`.
5. Auf dem Gerät installieren (`./gradlew installDebug` oder `assembleDebug` + `adb install -r`) und Baseline-Messung durchführen.

**Messprotokoll (zusammen mit dem Nutzer):**
- Szene A: Blatt auf einfarbigem Tisch, Handy ruhig gehalten, 10 s.
- Szene B: Blatt auf gemustertem oder unruhigem Untergrund, Handy ruhig gehalten, 10 s.
- Szene C: Handy langsam bewegt, 10 s.
- Pro Szene notieren: Sprünge, Aussetzer, mittlere Drift, ob Auto-Capture ausgelöst hat.

**Relevant Context:**
- [`DocumentDetector.kt`](app/src/main/java/com/hape/pdfscan/scan/DocumentDetector.kt) (`detectOnMat`, `detectRobust`, `DetectionResult`)
- [`ScanCameraController.kt`](app/src/main/java/com/hape/pdfscan/ui/scan/ScanCameraController.kt) (`analyzeFrame`, bereits Diagnosezeile jeden 5. Frame)
- [`ScanViewModel.kt`](app/src/main/java/com/hape/pdfscan/ui/scan/ScanViewModel.kt) (`onDiagnostics`)
- Muster für Tests: [`AutoCaptureControllerTest.kt`](app/src/test/java/com/hape/pdfscan/scan/AutoCaptureControllerTest.kt)

---

## Sub-Task 2: Schnelle Fixes (Eckreihenfolge, Aussetzer-Toleranz, relative Drift)

**Status:** [x] gekürzt umgesetzt (2026-11-20): nur B und G. Punkt C (Aussetzer-Toleranz) entfällt, weil die Baseline keine Aussetzer zeigte. Wartet auf Freigabe des Nutzers für Sub-Task 3.

**Umsetzung (gekürzt):**
- **B:** `DocumentGeometry.orderCorners` ist die einzige Ordnungsfunktion (Winkel um den Schwerpunkt, im Uhrzeigersinn, Start bei kleinster Summe x + y; Gleichstand: kleineres y, dann kleineres x). Neu: `DocumentGeometry.cornersFromPoints` (4 Punkte direkt über `orderCorners`, 5 bis 12 Punkte über die Extrema von x + y und x − y, danach ebenfalls `orderCorners`). `DocumentDetector.approxQuad` nutzt nur noch diese Funktion, die private Kopie `cornersFromPoints` ist entfernt. Der Aufrufer in `ScanViewModel` (manueller Zuschnitt) und `rotateCornersAndReorder` nutzen weiter `orderCorners`. Neue Tests in `DocumentGeometryTest`: gedrehte Rechtecke 0 bis 75° mit allen Permutationen, Uhrzeigersinn und Start bei kleinster Summe (30° bis 60°), `cornersFromPoints` mit 4 und mit 6 Punkten (45° ausgenommen: dort liegt eine Kante senkrecht zu x + y, die Extrema sind mehrdeutig). Bestehende `orderCorners`-Tests unverändert und grün.
- **G:** `AutoCaptureController.maxDriftPx` ersetzt durch `maxDriftFraction` (Konstante `DEFAULT_MAX_DRIFT_FRACTION = 0.01`). Schwelle = Anteil × Diagonale aus `DetectionResult.imageWidth/imageHeight`. **Startwert 0,01 = 14,7 px bei 1280x720** (heute 15 px). Der Aufrufer in `ScanViewModel` nutzt den Standardwert, keine Änderung nötig. `DetectionStats` (Sprungschwelle 15 px) bleibt bewusst unverändert, damit die Messwerte mit der Baseline vergleichbar bleiben. `AutoCaptureControllerTest` angepasst (`maxDriftFraction = 0.01f`) und um Fälle für 1280x720, 640x360 und 1920x1080 ergänzt.
- Validierung: `./gradlew test assembleDebug lint` erfolgreich (157 Tests), keine neuen Lint-Befunde.

**Messung nach Sub-Task 2 (Gerät, Debug-Build, Logcat `EdgeStats`, ca. 16:39 bis 16:41):**

Eigene Rückmeldung des Nutzers: Szene A erfolgreich, Szene B nicht, Szene C nicht. Nur der unruhige Hintergrund zeigt noch den alten Effekt.

Das Log enthält drei zusammenhängende Abschnitte ohne Pause (je mindestens 120 Frames). **Die Zuordnung zu den Szenen ist eine Annahme** (Reihenfolge im Log war nicht A, B, C; der ruhige Abschnitt ist nur einer):

| Abschnitt (Uhrzeit) | angenommene Szene | Frames | Sprünge pro 30 Frames (Mittel / Max) | Maximale Drift | Fallback-Frames im Abschnitt | Auto-Capture (Nutzer) |
|---|---|---|---|---|---|---|
| 16:39:44 bis 16:39:50 | A | 120 | 1,5 / 6 | 151 px | 13 | ausgelöst |
| 16:39:18 bis 16:39:31 | B oder C | 191 | 23,5 / 30 | 424 px | 3 | nicht ausgelöst |
| 16:40:44 bis 16:40:58 | B oder C | 207 | 22,8 / 30 | 349 px | 0 | nicht ausgelöst |

Vergleich mit der Baseline (A: 27,3 / 30, nicht ausgelöst; B: 2,8 / 14, ausgelöst; C: 16,5 / 29, nicht ausgelöst):
- Szene A ist ruhig und löst aus. In der Baseline war A die instabilste Szene. Ob das an B/G liegt oder an der Handhaltung, ist mit dieser Messung nicht belegt (Handy frei in der Hand, kein Stativ, einzelne Läufe).
- Die beiden unruhigen Abschnitte haben weiterhin 23 Sprünge pro 30 Frames und Driftwerte von 349 bis 424 px. Das sind Kandidatenwechsel, keine Eckenvertauschung oder Schwellenfrage. Gewinner-Masken wechseln weiter (alle fünf Masken im Abschnitt vertreten). Die Szene B der Baseline war dagegen ruhiger (2,8). Der Untergrund bzw. die Handhaltung ist nicht identisch, ein Rückschritt durch Sub-Task 2 ist daraus nicht ableitbar, aber auch nicht ausgeschlossen.
- Aussetzer (Fallback) treten in den unruhigen Abschnitten kaum auf (0 und 3). Das bestätigt die Entscheidung, Punkt C zu streichen.
- Die verbleibenden Sprünge stützen Sub-Task 3 (Tracker mit Hysterese): Springen zwischen Kandidaten ist die Hauptursache.
- Grenzen: Einzelläufe, Handy frei in der Hand, Zuordnung der Szenen nach Zeitstempel und Nutzerangabe. Für einen sauberen B/C-Vergleich müsste die Messung wiederholt werden (am besten Stativ, jede Szene mit eigener Markierung im Log).

**Intent:** Drei kleine, unabhängige Korrekturen, die Eckenvertauschung und die Auto-Capture-Resets beseitigen.

**Expected Outcomes:**
- Gleiche Geometrie liefert immer dieselbe Eckreihenfolge, egal über welchen Pfad (4 Punkte oder 5 bis 12 Punkte).
- Einzelne Fallback-Frames (z. B. 1 bis 3 in Folge) lassen den Rahmen stehen und setzen weder Glätter noch Countdown zurück.
- Die Drift-Schwelle für Auto-Capture ist relativ zur Bilddiagonale und damit unabhängig von der Framegröße.
- Messwerte aus Sub-Task 1 zeigen weniger Aussetzer und weniger Countdown-Resets.

**Todo List:**
1. **B – Eckreihenfolge:** Eine einzige Ordnungsfunktion verwenden. `orderCorners` auf Summe/Differenz (wie `cornersFromPoints`) umstellen oder umgekehrt, und beide Aufrufer darauf zeigen lassen. Tests in `DocumentGeometryTest` ergänzen: gedrehte Vierecke (z. B. 30° bis 60°), nicht nur achsenparallele. Bestehende Tests (`orderCorners_allPermutations_giveSameResult`, `orderCorners_axisAlignedRectangle`) müssen weiter bestehen.
2. **C – Aussetzer-Toleranz:** Neue reine Klasse oder Logik, die die letzte gute Erkennung bis zu N aufeinanderfolgende Fallback-Frames hält (N als Konstante, Startwert vorschlagen, am Gerät anpassen). Erst danach `cornerSmoother.reset()` und `autoCaptureController.reset()`. Einbau in `ScanViewModel.onDetectionResult`. Tests für: kurzer Aussetzer wird überbrückt, langer Aussetzer führt zum Reset, danach neue Erkennung startet sauber.
3. **G – relative Drift:** `AutoCaptureController.maxDriftPx` durch einen Anteil der Bilddiagonale ersetzen (z. B. Anteil statt 15 px, Startwert festlegen und am Gerät prüfen). Die Diagonale kommt aus `DetectionResult.imageWidth/imageHeight`. `AutoCaptureControllerTest` anpassen und Fälle für unterschiedliche Framegrößen ergänzen.
4. `./gradlew test assembleDebug lint`, auf dem Gerät installieren, Messprotokoll aus Sub-Task 1 wiederholen und vergleichen.

**Relevant Context:**
- [`DocumentGeometry.kt`](app/src/main/java/com/hape/pdfscan/scan/DocumentGeometry.kt:26) (`orderCorners`)
- [`DocumentDetector.kt`](app/src/main/java/com/hape/pdfscan/scan/DocumentDetector.kt:360) (`cornersFromPoints`, `approxQuad`)
- [`ScanViewModel.kt`](app/src/main/java/com/hape/pdfscan/ui/scan/ScanViewModel.kt:208) (`onDetectionResult`)
- [`AutoCaptureController.kt`](app/src/main/java/com/hape/pdfscan/scan/AutoCaptureController.kt:42) (`maxDriftPx`, `maxCornerDrift`)
- Tests: `DocumentGeometryTest`, `AutoCaptureControllerTest`, `CornerSmootherTest`

---

## Sub-Task 3a: Kandidatenbewertung (tatsächlich umgesetzt statt Sub-Task 3)

**Status:** [x] done (2026-11-20), am Gerät bestätigt.

**Auslöser:** Nach Sub-Task 2 sprang der Rahmen nur noch auf unruhigem Untergrund. Die Kandidaten-Diagnose (`EdgeCand`, farbige Linien in der Vorschau) zeigte: Der Gewinner war fast nie das Blatt. In 135 Frames hatte er nur in 2 Frames weniger als 50 % der Bildfläche, im Mittel 75 %. Mehrere große Vierecke über die ganze Szene (Ecken am Bildrand, Kanten durch Tischrand und Textur) lagen mit Scores von 0,60 bis 0,76 dicht beieinander, der Gewinner wechselte von Frame zu Frame. Ein Tracker hätte nur den falschen Rahmen ruhig gehalten.

**Umsetzung:**
- `DocumentGeometry.quadScore` hat einen flacheren Größenbonus (`SIZE_FLOOR` 0,6 bis 1,0 statt 0,1 bis 1,0) und einen Randabzug (`BORDER_CORNER_PENALTY` 0,5 je Ecke am Bildrand).
- Neu: `DocumentGeometry.cornersOnBorder` (Rand = `BORDER_MARGIN_FRACTION` 0,02 der kürzeren Bildseite).
- `DocumentDetector.detectRobust` übergibt die Randecken an `quadScore`.
- Diagnose (nur Debug): `DetectionResult.topCandidates` (Top 3 verschiedene Vierecke plus bestes Viereck unter 50 % Fläche), Logcat-Tag `EdgeCand`, farbige Kandidatenlinien in `ScanScreen`.
- Tests in `DocumentGeometryTest`: Blatt mit einem Viertel des Bildes schlägt Viereck über die Szene mit Randecken; Randecken reduzieren den Score; Zählung der Randecken.

**Messung auf dem unruhigen Untergrund (71 Frames, Handy ruhig):**

| | vorher | nachher |
|---|---|---|
| Sprünge pro 30 Frames | 23 bis 27 | 0 |
| Mittlere Drift | 130 bis 200 px | 1,8 px |
| Maximale Drift | 349 bis 424 px | 13,4 px |
| Fallback-Frames | 0 bis 3 | 0 |
| Auto-Capture | löst nicht aus | löst aus |

**Test des Nutzers:** Mehrere Läufe mit verschiedenen Entfernungen und Varianten des unruhigen Untergrunds, auch bewusst schräg gehaltenes Handy: Das Blatt wurde immer richtig erkannt, Auto-Capture hat immer ausgelöst.

**Hinweis:** Im Messlauf belegte der Gewinner nur 11 bis 12 % der Bildfläche, weil das Handy weiter weg gehalten wurde. Die Kandidatenlinien sind nur in Debug-Builds sichtbar.

**Entscheidung:** Keine weitere Optimierung. Tracker, One-Euro-Filter und Subpixel-Ecken sind nicht nötig und werden nicht umgesetzt.

---

## Sub-Task 3 (entfallen): Tracker mit Hysterese und One-Euro-Filter

**Status:** [-] entfällt (2026-11-20). Die Ursache lag in der Kandidatenbewertung, nicht in fehlendem Gedächtnis oder Glättung. Siehe Abschnitt „Sub-Task 3a: Kandidatenbewertung“ oben.

**Intent:** Das eigentliche Springen zwischen Kandidaten und das Zittern beheben. Der Tracker entscheidet, welcher Kandidat gilt. Der Filter glättet adaptiv statt mit festem Alpha.

**Expected Outcomes:**
- Der aktuelle Rahmen wird nur verlassen, wenn ein anderer Kandidat über mehrere Frames hinweg klar besser ist.
- Kandidaten nahe am aktuellen Rahmen werden bevorzugt.
- Im Ruhezustand ist das Zittern deutlich geringer, bei echter Bewegung folgt der Rahmen ohne spürbare Verzögerung.
- Der Slider „Randglättung“ (0 bis 10) bleibt erhalten und steuert die Glättung im Ruhezustand.
- Messwerte zeigen deutlich weniger Sprünge in Szene B (gemusterter Untergrund).

**Todo List:**
1. **D – Kandidatenliste:** `detectRobust` gibt nicht nur den Gewinner zurück, sondern die Top-K Kandidaten (Ecken, Score). Rückgabetyp in `DetectionResult` oder interner Zwischenwert; der Photo-Pfad (`processCapture`) nimmt weiter den besten.
2. **D – Tracker (reine Kotlin-Klasse, JVM-testbar):** Eingabe: Kandidatenliste je Frame. Ausgabe: gewählter Rahmen. Regeln:
   - Kandidaten, deren Ecken nahe am aktuellen Track liegen, bekommen einen Score-Bonus.
   - Ein anderer Kandidat übernimmt erst nach k aufeinanderfolgenden Frames, in denen er klar besser ist (Faktor und k als Konstanten, am Gerät abstimmen).
   - Ohne Track übernimmt der beste Kandidat sofort.
   - Zusammenspiel mit der Aussetzer-Toleranz aus Sub-Task 2 klären (Tracker hält den Rahmen, Toleranz entscheidet über Reset).
3. **E – One-Euro-Filter:** `CornerSmoother` intern auf One-Euro-Filter umstellen (pro Koordinate: Mindest-Cutoff aus `strength`, Beta für Geschwindigkeitsanpassung). Die öffentliche API (`smooth`, `reset`, `strength`, Konstanten) bleibt. Das Reset-Verhalten bei großem Sprung und Bildgrößenwechsel bleibt. `CornerSmootherTest` anpassen: Rauschdämpfung im Ruhezustand, schnelle Folge bei Bewegung, Strength 0 = Durchreichen.
4. Tracker in `ScanViewModel.onDetectionResult` vor dem Glätter einbauen, `reset()` an den bestehenden Stellen mitführen (`onCaptureDone`, `onCameraError`, `onAddAnotherPage`).
5. `./gradlew test assembleDebug lint`, Gerätetest mit dem Messprotokoll, Konstanten (k, Bonus, Cutoff, Beta) mit dem Nutzer live nachjustieren.

**Relevant Context:**
- [`DocumentDetector.kt`](app/src/main/java/com/hape/pdfscan/scan/DocumentDetector.kt:222) (`detectRobust`, Kandidatenschleife ab Zeile 277)
- [`DocumentGeometry.kt`](app/src/main/java/com/hape/pdfscan/scan/DocumentGeometry.kt:281) (`quadScore`)
- [`CornerSmoother.kt`](app/src/main/java/com/hape/pdfscan/scan/CornerSmoother.kt)
- [`ScanViewModel.kt`](app/src/main/java/com/hape/pdfscan/ui/scan/ScanViewModel.kt:142) (Glättungsstärke, Persistenz in `ScanFolderSettings`)
- Der Foto-Pfad (`processCapture`) nutzt `DocumentDetector.detect` direkt und bleibt unverändert, solange die Signatur kompatibel bleibt.

---

## Sub-Task 4 (optional): Subpixel-Ecken

**Status:** [-] entfällt (2026-11-20). Die Ecken sind stabil (mittlere Drift 1,8 px, maximal 13,4 px bei ruhig gehaltenem Handy), Zittern stört nicht mehr.

**Intent:** Das Zittern an der Quelle verringern. Ecken sollen aus angepassten Kantenlinien kommen und nicht aus `approxPolyDP`-Eckpunkten.

**Expected Outcomes:**
- Die Ecken sind bei gleichbleibender Szene von Frame zu Frame stabiler, auch ohne starke Glättung.
- Der Foto-Pfad profitiert ebenfalls (genauerer Crop).

**Todo List:**
1. Variante wählen und mit dem Nutzer abstimmen:
   - Einfach: `cornerSubPix` auf die vier Ecken, auf dem Graubild.
   - Besser: Konturpunkte pro Seite zuordnen, per `fitLine` anpassen, Ecken als Schnittpunkte benachbarter Linien. Dabei Ausreißer und fast parallele Linien behandeln.
2. Im Detektor nach der Kandidatenwahl anwenden. Bei Fehlschlag auf die bisherigen Ecken zurückfallen.
3. Reine Geometrie (Linienschnitt) in `DocumentGeometry` auslagern und per Unit-Test absichern. Der OpenCV-Teil lässt sich nur auf dem Gerät prüfen.
4. Messprotokoll wiederholen und mit Sub-Task 3 vergleichen.

**Relevant Context:**
- [`DocumentDetector.kt`](app/src/main/java/com/hape/pdfscan/scan/DocumentDetector.kt:322) (`approxQuad`)
- OpenCV 5: `approxPolyDP`, `arcLength`, `contourArea`, `convexHull` liegen in `org.opencv.geometry.Geometry`. Für `fitLine` und `cornerSubPix` vor der Nutzung prüfen, in welchem Paket sie in OpenCV 5 liegen (`Imgproc` oder `Geometry`).

---

## Abschluss (nach jeder freigegebenen Stufe)

- `CHANGELOG.md` unter `[Unreleased]` ergänzen.
- `AGENT.md`, Abschnitt „Bekannte Auffälligkeiten“, Punkt 2 „Rand springt“ aktualisieren (Ergebnis der Gerätetests).
- Bei Release: `VERSION`, `versionCode`, APKs nach `dist/` (siehe `AGENT.md`, Versionsregel).

## Umsetzungshinweis

Pro Sub-Task eine eigene Aufgabe starten, die diese Plandatei liest. Nach jedem Sub-Task Status hier auf `[x] done` setzen, Messwerte und gewählte Konstanten unter dem Sub-Task notieren und auf die Freigabe des Nutzers warten, bevor der nächste beginnt.
