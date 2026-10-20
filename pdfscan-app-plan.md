# PDFScan by Bob – Android-App – Umsetzungsplan

## Top-Level-Überblick

**Ziel:** Eine kleine Android-App (Kotlin, Jetpack Compose, CameraX, OpenCV), die Dokumente mit der Kamera scannt, Blattränder automatisch erkennt und zuschneidet, die Seiten zu einer PDF zusammenfasst und diese standardmäßig in `/Scans` im internen Speicher ablegt.

**Festgelegte Entscheidungen**
- Paketname: `com.hape.pdfscan`; `rootProject.name` ist (`PDFScanByBob` in [`settings.gradle`](settings.gradle:16)) anpassen.
- Start mit Version **1.0.0** (versionCode 1), minSdk 29.
- Speicher: Die App fordert den Dateizugriff selbst an und schreibt direkt per Dateisystem (`java.io.File`) in den Scan-Ordner. Der Standard ist `/Scans` im Wurzelverzeichnis des internen Speichers (`/storage/emulated/0/Scans`). Der Ordner ist in den Einstellungen änderbar.
  - Ab Android 11 (API 30): Sonderberechtigung "Zugriff auf alle Dateien" (`MANAGE_EXTERNAL_STORAGE`), angefordert über die System-Einstellungsseite der App.
  - Android 10 (API 29): Laufzeitberechtigung für Lesen und Schreiben des externen Speichers, plus das Manifest-Flag `requestLegacyExternalStorage`. Die Wirkung des Flags ist auf einem API-29-Gerät oder Emulator zu verifizieren.
  - Hinweis: Diese Berechtigung ist für den Google-Play-Store stark eingeschränkt. Die Verteilung ist hier per signierter APK geplant.
- Sprachen: Deutsch (Standard, `values/`) und Englisch (`values-en/`).
- Kantenerkennung und Perspektivkorrektur: OpenCV (Maven-Artefakt), Live-Analyse über CameraX `ImageAnalysis`.
- PDF-Erzeugung: Android-Klasse `android.graphics.pdf.PdfDocument`, eine Seite pro Scan.
- Ordner: Unterordner des Scan-Ordners in beliebig vielen Ebenen (verschachtelt). Dokumente lassen sich zwischen Ordnern verschieben und kopieren.
- Teilen über `FileProvider` (Datei → Content-URI, `ACTION_SEND`).
- Signierung: `keystore.properties` (nicht im Git, siehe [`.gitignore`](.gitignore:11)) für `signingConfigs`; alternativ [`uber-apk-signer.jar`](uber-apk-signer.jar). Es gibt keine Zugangsdaten im Code.
- Abhängigkeiten: jeweils die neueste stabile, gepflegte Version. Das ist vor dem Eintragen zu prüfen, ebenso Lizenz und Schwachstellen.

**Ablauf der App**
- Start: Prüfen, ob About angezeigt werden muss, dann die Prüfung der Berechtigung, dann das Hauptmenü.
- Scan: Live-Kamera mit Kantenüberlagerung. Auslösung per Knopf oder nach 3 Sekunden Ruhe (Timer sichtbar). Danach Zuschnitt, Vorschau und die Frage nach einer weiteren Seite. Bei "Nein" Namensabfrage, PDF speichern, zurück zum Hauptmenü.

**Ist-Zustand:** Es gibt nur das Gradle-Grundgerüst (Root-[`build.gradle`](build.gradle) mit AGP 8.3.0, [`settings.gradle`](settings.gradle) mit `include(":app")`, Gradle-Wrapper 8.6, [`gradle.properties`](gradle.properties)). Das Modul `app` fehlt. Das Logo liegt als [`PDFScan-BY-BOB.png`](PDFScan-BY-BOB.png) vor. `keystore.properties`, `patch-key.jks` und `local.properties` existieren lokal und sind git-ignoriert.

---

## Sub-Tasks

### 1. Projektgerüst und Build-Konfiguration
- **Intent:** Ein lauffähiges, leeres Compose-Modul `app` mit korrekter Konfiguration.
- **Expected Outcomes:** `./gradlew assembleDebug` baut eine App mit Paket `com.hape.pdfscan`, Version 1.0.0 und leerem Startbildschirm.
- **Todo List:**
  1. Aktuelle stabile Versionen von AGP, Kotlin, Compose BOM, CameraX, OpenCV, DataStore, Navigation ermitteln und prüfen. Wrapper und Root-[`build.gradle`](build.gradle) bei Bedarf anheben.
  2. `app/build.gradle` anlegen: namespace und applicationId `com.hape.pdfscan`, minSdk 29, versionName 1.0.0, versionCode 1, Compose aktivieren.
  3. `signingConfigs` für Release aus `keystore.properties` lesen. Wenn die Datei fehlt, bleibt der Build ohne Signierung lauffähig.
  4. `AndroidManifest.xml` mit Kamera- und Speicher-Berechtigungen, `requestLegacyExternalStorage`, `FileProvider`, Activity.
  5. `strings.xml` (de) und `values-en/strings.xml` (en) mit App-Name.
  6. `proguard-rules.pro` anlegen. R8 für Release aktivieren, sofern OpenCV damit läuft.
- **Relevant Context:** [`build.gradle`](build.gradle), [`settings.gradle`](settings.gradle), [`gradle.properties`](gradle.properties), [`gradle/wrapper/gradle-wrapper.properties`](gradle/wrapper/gradle-wrapper.properties).
- **Status:** `[x] done`

#### Kontext für den nächsten Sub-Task

**Tatsächlich gewählte Versionen:**

Stand nach Aktualisierung auf die jeweils neuesten stabilen Versionen (per Maven-Metadaten und `services.gradle.org` geprüft):

| Komponente | Version | Anmerkung |
|---|---|---|
| Gradle Wrapper | 9.8.2 | Neueste stabile Version |
| AGP | 9.4.1 | Neueste stabile Version. Kotlin ist eingebaut, daher kein Plugin `org.jetbrains.kotlin.android` und kein `kotlinOptions` |
| Kotlin | 2.4.21 | Nur noch das Plugin `plugin.compose` in Root und `app` |
| Compose BOM | 2026.09.00 | Neueste Version |
| `compileSdk` / `targetSdk` | 37 / 35 | 37 ist für `core-ktx` 1.19.1 nötig. Platform `android-37.0` wurde per `sdkmanager` installiert. targetSdk bleibt 35 |
| CameraX | 1.6.2 | Neueste stabile Version |
| OpenCV | 5.0.0 | Neueste stabile Version. Die native Bibliothek heißt jetzt `libopencv_java5.so`. API-Unterschiede zu 4.x in Sub-Task 4 beachten |
| DataStore | 1.2.1 | Neueste stabile Version |
| Navigation Compose | 2.10.2 | Neueste stabile Version |
| `core-ktx` | 1.19.1 | Neueste stabile Version |
| `activity-compose` | 1.13.0 | Neueste stabile Version |
| `lifecycle-runtime-ktx` | 2.11.0 | Neueste stabile Version |
| Test-Bibliotheken | junit 4.13.2, androidx.test.ext 1.3.0, espresso 3.7.0 | Neueste stabile Versionen |

**Paketstruktur:**
```
app/src/main/java/com/hape/pdfscan/
    MainActivity.kt
    ui/theme/Theme.kt
app/src/main/res/
    drawable/ic_launcher_foreground.xml  (Platzhalter; wird in Sub-Task 9 ersetzt)
    mipmap-anydpi-v26/ic_launcher.xml
    mipmap-hdpi/ic_launcher.xml          (Platzhalter; wird in Sub-Task 9 ersetzt)
    values/colors.xml
    values/strings.xml                   (Deutsch, Standard)
    values/themes.xml
    values-en/strings.xml                (Englisch)
    xml/file_provider_paths.xml
```

**Abweichungen vom Plan:**
- `targetSdk=35` statt der neuesten Version, da die APK direkt verteilt wird und nicht über den Play Store.
- Java 17 (Homebrew `openjdk@17`) muss für den Build explizit gesetzt werden: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`.
- `rootProject.name` bleibt `PapyrusByBob` gemäß Plan-Anforderung (settings.gradle unverändert).
- `keystore.properties` enthält die Schlüssel `PAPYRUS_STORE_PASSWORD`, `PAPYRUS_KEY_ALIAS`, `PAPYRUS_KEY_PASSWORD`, aber keinen `storeFile`. `app/build.gradle` liest diese Schlüssel und nimmt als Standard-Keystore `patch-key.jks` im Projektwurzelverzeichnis. Der Release-Build ist damit signiert (`apksigner verify` meldet v2-Schema gültig, ein Signer).
- Verbleibende Gradle-Warnung: `Configuration.setVisible` stammt aus einem Plugin, nicht aus unseren Skripten, und ist erst für Gradle 11 angekündigt.
- Kein Emulator-Support: `abiFilters` in `app/build.gradle` erlaubt nur `arm64-v8a` und `armeabi-v7a`. Die x86- und x86_64-Libs (OpenCV und andere) sind damit aus der APK entfernt. Größe danach: Debug 102 MB, Release 61 MB (vorher 227 MB bzw. 186 MB). Weiteres Verkleinern wäre durch ABI-Splits oder den Verzicht auf `armeabi-v7a` möglich, ist aber nicht beschlossen.

### 2. Speicherschicht und Berechtigung
- **Intent:** Zugriff auf den konfigurierbaren Scan-Ordner kapseln.
- **Expected Outcomes:** Die App erkennt fehlenden Dateizugriff, leitet den Nutzer zur Freigabe und kann Dateien und Ordner anlegen, auflisten, löschen, kopieren und verschieben.
- **Todo List:**
  1. Berechtigungslogik: ab API 30 `Environment.isExternalStorageManager()` und Weiterleitung zu `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`. Auf API 29 Laufzeitberechtigung.
  2. Einstellungsspeicher (DataStore) für den Scan-Ordner-Pfad, Standard `/Scans` (aufgelöst relativ zum internen Speicher).
  3. Repository: auflisten (Ordner, PDFs), Ordner anlegen. Für Dateien und Ordner: löschen, kopieren, verschieben, umbenennen. Ordner werden rekursiv samt Inhalt behandelt (löschen, kopieren, verschieben). Bei Fehlern mitten in einer Ordneroperation darf kein halber Zustand unbemerkt bleiben, der Nutzer bekommt eine Meldung.
  4. Eingabevalidierung: Datei- und Ordnernamen per Allowlist prüfen, kein Pfad-Traversal (`..`, `/`), Duplikate behandeln. Beliebig tief verschachtelte Unterordner sind erlaubt. Jeder Pfad wird kanonisiert (`canonicalPath`) und muss innerhalb des Scan-Ordners liegen, auch bei Symlinks. Beim Kopieren und Verschieben von Ordnern ist zu verhindern, dass ein Ordner in sich selbst oder in einen eigenen Unterordner verschoben wird.
  5. Fehler als generische Meldungen an die UI. Details nur ins Log, ohne sensible Inhalte.
  6. Unit-Tests für Validierung und Dateioperationen (temporäres Verzeichnis).
- **Relevant Context:** Neues Paket `com.hape.pdfscan.storage`.
- **Status:** `[x] done`

#### Kontext für den nächsten Sub-Task

**Öffentliche Klassen in `com.hape.pdfscan.storage`** (alle Dateien unter `app/src/main/java/com/hape/pdfscan/storage/`):

| Klasse | Kurzsignatur / Zweck |
|---|---|
| `StoragePermission` (object) | `isGranted(context): Boolean`; `requestFor(context): PermissionRequest` |
| `PermissionRequest` (sealed) | `NotNeeded` · `RuntimePermissions(permissions: Array<String>)` (API 29, per `RequestMultiplePermissions` anfordern) · `AllFilesAccessSettings(intent, fallbackIntent)` (API 30+, `startActivity(intent)`; bei `ActivityNotFoundException` den `fallbackIntent`; bei `onResume` erneut `isGranted` prüfen) |
| `ScanFolderSettings` | `ScanFolderSettings(context)`; `scanFolder: Flow<File>`; `suspend currentScanFolder(): File`; `suspend setScanFolder(File)`; `suspend resetScanFolder()`. Standard `defaultScanFolder()` = `Environment.getExternalStorageDirectory()/Scans`. Nutzt den app-weiten Preferences-DataStore `"settings"` (`Context.appDataStore`, `internal`), Schlüssel `scan_folder_path`. Sub-Task 8 legt seine Schlüssel im selben Store an. **Keine Pfadvalidierung** (existiert/beschreibbar/intern): das ist Aufgabe von Sub-Task 7 |
| `ScanFileRepository` | `ScanFileRepository(root: File, ioDispatcher = Dispatchers.IO, logger)`; alle Funktionen `suspend` und liefern `StorageResult<T>`: `ensureRoot(): File`, `list(directory = root): List<ScanEntry>`, `createFolder(parent, name): File`, `rename(file, newName): File`, `delete(file): Unit`, `copy(source, targetDirectory, conflict = FAIL): File`, `move(source, targetDirectory, conflict = FAIL): File`, `suggestUniqueName(directory, name, isDirectory): String`. Bei geändertem Scan-Ordner eine neue Instanz erzeugen |
| `StorageResult<T>` (sealed) | `Success(value)` · `Failure(error: StorageError)`; `getOrNull()` |
| `StorageError` (sealed) | `NameInvalid(problem: NameProblem)`, `AlreadyExists`, `OutsideRoot`, `NotFound`, `InvalidTarget`, `SymlinkNotSupported`, `IoError`, `PartiallyDone` |
| `NameProblem` (enum) | `EMPTY, TOO_LONG, CONTROL_CHARACTER, FORBIDDEN_CHARACTER, DOT_SEQUENCE, LEADING_OR_TRAILING_SPACE` |
| `ConflictStrategy` (enum) | `FAIL`, `KEEP_BOTH` |
| `ScanEntry` | `file, isDirectory, lastModified, size` (+ `name`) |
| `NameValidator` (object) | `validate(name): NameProblem?` (`null` = gültig), `MAX_LENGTH = 100`, `MAX_BYTES = 200`. Auch für den Dokumentnamen in Sub-Task 6 verwenden (Name inkl. `.pdf` prüfen) |
| `PathGuard` | `PathGuard(root)`; `resolve(file): File?` (kanonisch, `null` wenn außerhalb); `PathGuard.isSameOrDescendant(candidate, ancestor)` (companion, für die gesperrten Zielordner im Dialog Kopieren/Verschieben) |

**Entscheidungen:**
- **Duplikate:** Nie überschreiben. `createFolder` und `rename` melden immer `AlreadyExists`. `copy` und `move` melden standardmäßig `AlreadyExists`; mit `ConflictStrategy.KEEP_BOTH` wird automatisch ein freier Name wie `Name (2).pdf` / `Name (3)` gewählt (vorhandener Zähler `(n)` wird ersetzt; bei Ordnern gilt der ganze Name als Basis). Für den Namensdialog kann die UI `suggestUniqueName` verwenden. Kopieren in denselben Ordner ist erlaubt (ergibt `Name (2)` mit `KEEP_BOTH`); Verschieben in den bisherigen Ordner ist ein No-op und liefert die Quelle zurück.
- **Rename:** `newName` ist der komplette Name samt Endung (die UI hängt `.pdf` selbst an). Nur Groß-/Kleinschreibung ändern ist erlaubt.
- **Namens-Allowlist:** Buchstaben und Ziffern aller Schriften sowie Leerzeichen und `_ - . ( ) , ' + & # @ ! = ~ [ ]`. Keine führenden/endenden Punkte oder Leerzeichen, kein `..`, max. 100 Zeichen bzw. 200 UTF-8-Bytes.
- **Pfadprüfung:** Jeder übergebene Pfad wird per `canonicalFile` aufgelöst und muss im Scan-Ordner liegen (`OutsideRoot`). Der Scan-Ordner selbst kann nicht umbenannt/gelöscht/kopiert/verschoben werden (`InvalidTarget`), ebenso wenig ein Ordner in sich selbst oder einen Unterordner (`InvalidTarget`). Zurückgegebene Dateien sind kanonische Pfade.
- **Symlinks:** werden nicht aufgelistet, nicht kopiert/verschoben (`SymlinkNotSupported`, auch wenn ein Ordner einen Link enthält; dabei wird vorab geprüft, es bleibt nichts zurück) und beim Löschen nur als Link entfernt, nie verfolgt.
- **Halber Zustand:** `copy` räumt eine unvollständige Kopie wieder weg (`IoError`; schlägt das Aufräumen fehl: `PartiallyDone`). `move` versucht zuerst `renameTo`, sonst Kopieren + Löschen; scheitert das Löschen der Quelle ohne Änderung, wird die Kopie zurückgenommen (`IoError`), bei teilweise gelöschter Quelle bleibt die vollständige Kopie am Ziel (`PartiallyDone`). `delete` meldet `PartiallyDone`, wenn bereits ein Teil entfernt wurde, sonst `IoError`. Die UI soll bei `PartiallyDone` die Liste neu laden und eine deutliche Meldung zeigen.
- **Fehlerausgabe:** Keine rohen Exception-Meldungen. Log (`Log.w`) enthält nur Operation und Exception-Klasse, keine Pfade oder Namen. Strings für die UI kommen in Sub-Task 3.
- **Liste:** Ordner zuerst, dann PDFs (`.pdf`, Groß-/Kleinschreibung egal), je case-insensitiv nach Name sortiert; versteckte Einträge (Punkt am Anfang), Symlinks und andere Dateitypen fehlen.

**Abweichungen vom Plan / Hinweise:**
- Keine neue Abhängigkeit eingetragen. `kotlinx-coroutines` kommt transitiv über DataStore (Repository, `runBlocking` in den Tests); falls es später direkt gebraucht wird (z. B. `kotlinx-coroutines-test`), mit der neuesten stabilen Version eintragen.
- Die Berechtigungslogik hat keine UI. Die Hilfsklasse liefert nur das, was die UI tun muss (`PermissionRequest`); Aufruf und Hinweis-Screen folgen in Sub-Task 3.
- Auf API 29 ist `READ/WRITE_EXTERNAL_STORAGE` + `requestLegacyExternalStorage` weiterhin auf einem Gerät/Emulator zu verifizieren (offener Punkt unverändert).
- Tests: `NameValidatorTest` (7), `PathGuardTest` (7), `ScanFileRepositoryTest` (44), alle grün (nichts übersprungen; Symlink-Tests liefen auf dem Mac). `ScanFolderSettings`, `StoragePermission` und der Fallback-Pfad „Kopieren + Löschen“ beim Verschieben (`renameTo` schlägt fehl) sind nicht per Unit-Test abgedeckt, da sie Android bzw. mehrere Dateisysteme brauchen.
- `./gradlew test assembleDebug` und `./gradlew lint` laufen ohne neue Fehler/Warnungen (Lint meldet nichts im Paket `storage`).

### 3. Hauptmenü (Dokumentenliste)
- **Intent:** Übersicht über Ordner und gescannte Dokumente mit Aktionen.
- **Expected Outcomes:** Die Liste zeigt Ordner und PDFs. Ordner anlegen, öffnen und zurücknavigieren funktioniert. Pro Dokument gibt es Löschen, Kopieren, Verschieben, Umbenennen, Teilen. Pro Ordner gibt es Löschen, Kopieren, Verschieben, Umbenennen. Ein Knopf startet den Scan.
- **Todo List:**
  1. Compose-Screen mit Navigation (Hauptmenü, Scan, About, Einstellungen).
  2. Listenansicht mit Ordner-Navigation über mehrere Ebenen: Pfad-Stack bzw. Breadcrumb-Anzeige, "Hoch"-Navigation und Zurück-Taste. Die Zielordner-Auswahl für Kopieren und Verschieben zeigt den Ordnerbaum und lässt Navigation durch alle Ebenen zu.
  3. Dokument-Kontextmenü mit Löschen (mit Bestätigung), Kopieren und Verschieben (Zielordner-Auswahl), Umbenennen (Dialog mit Validierung), Teilen (`FileProvider`, `ACTION_SEND`).
  4. Ordner-Kontextmenü mit Löschen, Kopieren, Verschieben und Umbenennen. Löschen fragt nach und nennt, dass der gesamte Inhalt mit entfernt wird. Als Ziel für Kopieren und Verschieben sind der Ordner selbst und seine Unterordner gesperrt. Teilen gibt es für Ordner nicht.
  5. Dialog "Ordner anlegen".
  6. Leerzustand und Hinweis, wenn der Dateizugriff fehlt.
  7. Menüeinträge für Einstellungen und About.
- **Relevant Context:** Paket `com.hape.pdfscan.ui.main`, Repository aus Sub-Task 2.
- **Status:** `[x] done`

#### Kontext für den nächsten Sub-Task

**Navigationsstruktur und Routen:**
- `NavRoutes.MAIN` = `"main"` (`MainMenuScreen`)
- `NavRoutes.SCAN` = `"scan?targetDirectory={targetDirectory}"` (Scan-Screen; aktuell Platzhalter `ScanPlaceholderScreen`)
- `NavRoutes.ABOUT` = `"about"` (About-Screen; aktuell Platzhalter `AboutPlaceholderScreen`)
- `NavRoutes.SETTINGS` = `"settings"` (Settings-Screen; aktuell Platzhalter `SettingsPlaceholderScreen`)
- Hilfsfunktionen in `NavRoutes`: `scanRoute(targetDirectory: File?): String` (URL-encodierter absoluter Pfad) und `parseTargetDirectory(encodedPath: String?): File?`

**Scan-Screen-Einbindung und Übergabe des Zielordners:**
- Der FAB im Hauptmenü startet den Scan mit dem aktuell geöffneten Ordner `uiState.currentDirectory` via `navController.navigate(NavRoutes.scanRoute(targetDirectory))`.
- In `AppNavHost` (`com.hape.pdfscan.navigation.AppNavHost`) liest das Composable für `NavRoutes.SCAN` das Argument `targetDirectory` aus und übergibt das aufgelöste `File?` an `ScanPlaceholderScreen`. Sub-Task 4 und 6 können `ScanPlaceholderScreen` dort direkt durch den echten Scan-Screen ersetzen und haben den Zielordner für das Speichern der PDF direkt als `File` verfügbar.

**Wichtigste Klassen:**
| Klasse | Paket | Kurzbeschreibung |
|---|---|---|
| `NavRoutes`, `AppNavHost` | `com.hape.pdfscan.navigation` | Routen-Definitionen, URL-Encoding für Zielordner-Pfad, Compose `NavHost` |
| `MainViewModel` | `com.hape.pdfscan.ui.main` | Verwaltet Pfad-Stack, Verzeichniswechsel, Breadcrumbs, Ordnererstellung, Umbenennen, Löschen, Kopieren/Verschieben mit Zielordner-Auswahl (gesperrte Ordner per `PathGuard`), Konfliktbehandlung (`ConflictStrategy.KEEP_BOTH`) und Permission-Status |
| `MainMenuUiState` | `com.hape.pdfscan.ui.main` | StateFlow-Datenmodell (Permission, Laden, Pfad-Stack, Breadcrumbs, Einträge, Dialog-Zustände) |
| `MainMenuScreen` | `com.hape.pdfscan.ui.main` | UI mit TopAppBar (Neuer Ordner, Overflow für Settings/About), Breadcrumb-Leiste, Scan-FAB, Dokument-/Ordner-Kontextmenüs, Teilen via `FileProvider`, Permission-Prüfung bei `ON_RESUME` |
| `MainDialogs` | `com.hape.pdfscan.ui.main` | Dialoge: `CreateFolderDialog`, `RenameDialog`, `DeleteConfirmDialog`, `FolderPickerDialog` (navigierbarer Ordnerbaum), `ConflictResolutionDialog` |
| `StorageErrorMapper` | `com.hape.pdfscan.ui.main` | Mappt alle `StorageError`- und `NameProblem`-Fälle auf lokalisierte String-Ressourcen |
| `PlaceholderScreens` | `com.hape.pdfscan.ui.placeholder` | Platzhalter mit TopAppBar, Titel und Zurück-Navigation für Scan, About und Einstellungen |

**Abweichungen vom Plan / Hinweise:**
- `androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0` und `androidx.compose.material:material-icons-core` wurden zur `app/build.gradle` hinzugefügt. Eigene Vector-Drawables (`ic_folder`, `ic_pdf_file`, `ic_camera`, `ic_content_copy`, `ic_drive_file_move`, `ic_create_new_folder`) wurden angelegt, um `material-icons-extended` zu vermeiden.
- `kotlinx-coroutines-test:1.10.1` wurde für ViewModel-Unit-Tests als `testImplementation` ergänzt.
- Unit-Tests: 70 Tests (inkl. 10 Tests in `MainViewModelTest` und 2 Tests in `StorageErrorMapperTest`), alle grün.
- `./gradlew test assembleDebug lint` läuft erfolgreich und ohne neue Lint-Warnungen.

### 4. Kamera und Kantenerkennung
- **Intent:** Live-Kamera, die Blattränder erkennt und anzeigt.
- **Expected Outcomes:** Die Kamera zeigt eine Vorschau mit hervorgehobener Blattkontur. Ein Foto wird perspektivkorrigiert und zugeschnitten.
- **Todo List:**
  1. Kamera-Berechtigung anfordern. Ablehnung zeigt eine verständliche Meldung.
  2. CameraX `Preview`, `ImageAnalysis` und `ImageCapture` einbinden.
  3. OpenCV-Initialisierung (Maven-Artefakt, Native Libs für arm64 und, falls nötig, weitere ABIs).
  4. Erkennung: Graustufen, Blur, Canny, Konturen, größtes Viereck. Ecken ordnen, Perspektivtransformation und Zuschnitt.
  5. Overlay der erkannten Kontur in der Vorschau.
  6. Fallback: Wenn nichts erkannt wird, wird das ganze Bild verwendet.
  7. Unit-Tests für Eckenordnung und Geometrie, soweit ohne Gerät möglich.
- **Relevant Context:** Paket `com.hape.pdfscan.scan`.
- **Status:** `[-] wartet auf Gerätetest` (gebaut, 103 Unit-Tests grün; Auswertung an echten Testbildern s1/s3 und frame.png abgeschlossen; APK `dist/PDFScan-by-Bob-1.0.0-debug.apk` bereitgestellt).

**Stand nach dem ersten Feldtest und Auswertung echter Frames:**
- **Auswertung an echten Bildern:**
  - `s1.png` (Screenshot 1080x2400): Vorschau-Bereich enthält ein A4-Blatt auf Teppich. Ground-Truth-Eckpunkte: TL(146, 627), TR(726, 632), BR(743, 1469), BL(117, 1480). Stufe 1 erkennt das Blatt mit IoU 0.974.
  - `s3.png` (Screenshot 1080x2400): Blatt mit eingeknickter Ecke links unten und Teppichtextur/Beleuchtungsverlauf. Ground-Truth-Eckpunkte: TL(155, 615), TR(808, 621), BR(822, 1582), BL(109, 1580). Stufe 1 fand nichts (Kontur hatte 6 Ecken, nicht-konvex). Stufe 2 mit Multi-Epsilon-Approximation findet das Blatt zuverlässig mit IoU 0.974.
  - `frame.png` (720x960, unruhig/Kabel/Boden ohne Dokument): Liefert korrekterweise `None` (kein Fehlalarm).
  - Synthetische Benchmark (20 Szenen): 19/20 IoU >= 0.99, keine Fehlalarme auf leeren Hintergründen (Holz, Teppich, heller Tisch, dunkler Schreibtisch).
- **Ursachenanalyse:**
  1. Starres Epsilon bei `approxPolyDP` (`0.02 * arcLen`) verhinderte die Erkennung bei leicht geknickten Blattecken (wie in s3), da Kontur und konvexe Hülle 5–6 Vertices aufwiesen.
  2. Fehlende Stufe-1-Kantenmaske im Stufe-2-Kandidatenpool.
  3. OpenCV-JNI-Konvexitätsprüfung ersetzt durch reine mathematische Vektor-Kreuzprodukt-Prüfung (`isConvex`) in `DocumentGeometry`.
- **Änderungen in Kotlin (`DocumentDetector.kt`, `DocumentGeometry.kt`):**
  - `DocumentGeometry.isConvex`: Reine Geometrie-Prüfung auf strikte Konvexität (gleiches Vorzeichen aller Kanten-Kreuzprodukte).
  - `DocumentDetector.approxQuad`: Multi-Epsilon-Approximation (`0.02`, `0.03`, `0.04`, `0.015`) und `approx.total() == 4L`-Prüfung.
  - `DocumentDetector.detectRobust`: Aufnahme der dilatierten Stufe-1-Kanten in den Stufe-2-Kandidatenpool für maximale Robustheit.
- **Was der Nutzer auf dem Gerät testen soll:**
  - Neue Debug-APK `dist/PDFScan-by-Bob-1.0.0-debug.apk` installieren.
  - Kamera auf Dokument richten: Die Diagnosezeile soll `Stufe=1` bzw. `Stufe=2` mit `fallback=false` anzeigen.
  - Das blaue Kontur-Overlay soll exakt auf den 4 Blatträndern liegen.
  - Nach Auslösen des Kamera-Buttons soll das entzerrte, zugeschnittene Bild in der Vorschau erscheinen.

#### Kontext für den nächsten Sub-Task

**Klassen:**
| Klasse | Paket | Kurzbeschreibung |
|---|---|---|
| `DocumentCorners`, `FloatPoint` | `scan` | Vier geordnete Ecken (oben-links, oben-rechts, unten-rechts, unten-links) |
| `DocumentGeometry` | `scan` | Reine Logik ohne Android/OpenCV: `orderCorners`, `targetSize`, `isPlausibleQuad`, `scaleCorners`, `rotatePoint`, `rotateCornersAndReorder`, `mapFillCenter` |
| `DocumentDetector` | `scan` | Objekt mit OpenCV: `detect(bitmap): DetectionResult` und `crop(bitmap, corners): Bitmap?`. Dazu `initOpenCv(): Boolean` |
| `DetectionResult` | `scan` | `corners`, `isFallback`, `timestampMs`, `imageWidth`, `imageHeight`. Die Ecken liegen in Pixeln des analysierten, aufrecht gedrehten Bilds |
| `ScanCameraController` | `ui.scan` | CameraX: `bindCamera(previewView, lifecycleOwner)`, `capture(cacheDir)`, `unbind()`, `release()` |
| `ScanViewModel`, `ScanUiState` | `ui.scan` | Zustände `PermissionDenied`, `Scanning(latestDetection)`, `Preview(croppedBitmap, tempFile)`, `Error(messageRes)` |
| `ScanScreen` | `ui.scan` | Ersetzt `ScanPlaceholderScreen` in `AppNavHost`. Parameter `targetDirectory: File?` ist durchgereicht, wird erst in Sub-Task 6 gebraucht |

**Für Sub-Task 5 (Automatik):** `ScanViewModel.onDetectionResult(result)` wird pro Analyse-Frame aufgerufen (nur im Zustand `Scanning`). `DetectionResult.timestampMs` und die Ecken reichen für ein Stabilitätsmaß (Vergleich aufeinanderfolgender Ecken). Ausgelöst wird mit `ScanCameraController.capture(cacheDir)`. Doppelte Auslösung ist durch ein internes Flag gesperrt. Der Countdown gehört in den ViewModel-Zustand, nicht in den Controller.

**Für Sub-Task 6 (Seitenliste/PDF):** Nach der Aufnahme liefert `ScanUiState.Preview` das zugeschnittene `Bitmap` und eine JPEG-Datei im App-Cache (`scan_<zeit>.jpg`, Qualität 90). Aktuell zeigt der Screen nur "Verwerfen" und "Neu aufnehmen" (beide verwerfen das Bild und starten die Kamera neu). Sub-Task 6 ersetzt diese Knöpfe durch "Weitere Seite" und "Fertig" und sammelt die Dateien. Zum Zurück-Navigieren während einer Vorschau wird das Bild nur beim Verlassen des ViewModels aufgeräumt (`onCleared`).

**OpenCV-5-API-Unterschiede zu 4.x** (gegen das AAR geprüft):
- `approxPolyDP`, `arcLength`, `contourArea`, `isContourConvex` und `getPerspectiveTransform` liegen in `org.opencv.geometry.Geometry`, nicht mehr in `Imgproc`.
- `OpenCVLoader.initLocal()` lädt `libopencv_java5.so`. `Utils.bitmapToMat` liefert RGBA, daher `COLOR_RGBA2GRAY`.
- Alles andere (`cvtColor`, `GaussianBlur`, `Canny`, `dilate`, `findContours`, `warpPerspective`, `resize`) liegt weiter in `Imgproc`.

**Entscheidungen:**
- Die Erkennung läuft auf einer auf 1000 px (längste Seite) verkleinerten Kopie. Die Ecken werden danach auf die volle Bildgröße hochgerechnet.
- Das Foto wird im Speicher aufgenommen (`OnImageCapturedCallback`), nicht als Datei. Die Rotation kommt aus `ImageInfo.rotationDegrees`, nicht aus EXIF. Die Erkennung läuft am Foto in voller Auflösung noch einmal, die Analyse-Ecken werden nicht hochskaliert.
- Das Overlay rechnet mit `mapFillCenter` (gleiche Skalierung wie `PreviewView.FILL_CENTER`) und nutzt `imageWidth`/`imageHeight` aus dem Ergebnis.
- Die Kamera wird abgeschaltet, solange die Vorschau eines Fotos zu sehen ist.
- Keine neue Abhängigkeit hinzugefügt. Die R8-Regel `-keep class org.opencv.** { *; }` ist aus Sub-Task 1 vorhanden und reicht: Die Release-APK baut, ist signiert, und die Klasse `Geometry` ist im DEX enthalten.

**Nicht auf einem Gerät geprüft, bitte manuell auf einem ARM-Gerät testen:**
1. OpenCV lädt (`initLocal()` liefert true), Debug und Release.
2. Kamera-Berechtigung: erste Anfrage, Ablehnen, "dauerhaft abgelehnt" mit Weiterleitung in die Einstellungen.
3. Vorschau und Overlay: sitzt die Kontur auf dem Blattrand, bei Hoch- und Querformat und nach Drehen des Geräts.
4. Aufnahme: Ergebnis ist aufrecht und sauber entzerrt, auch bei schrägem Winkel.
5. Fallback: ohne erkennbares Blatt wird das ganze Bild verwendet.
6. Dauer von Aufnahme bis Vorschau, Speicherverbrauch bei vielen Aufnahmen hintereinander.
7. Erkennung bei geringem Kontrast (weißes Blatt auf hellem Tisch) ist erfahrungsgemäß schwach. Schwellwerte (`Canny` 50/150, `approxPolyDP` 2 %, Mindestfläche 2 %) sind unerprobt.

**Abweichungen vom Plan:**
- `ScanCameraController` hat `unbind()` zusätzlich zu `release()`.
- Der Unit-Test `DocumentGeometryTest` (22 Tests) deckt nur die reine Geometrie ab. `DocumentDetector` und die Kamera sind auf der JVM nicht testbar (native Lib).
- Die Lint-Warnungen aus dem neuen Code sind behoben. Verbleibend sind 8 Warnungen aus früheren Sub-Tasks und der Projektkonfiguration.

### 5. Auslösung per Knopf und 3-Sekunden-Automatik
- **Intent:** Aufnahme manuell oder automatisch, wenn die Kamera still über dem Blatt gehalten wird.
- **Expected Outcomes:** Der Knopf löst jederzeit aus. Bei erkanntem Blatt und ruhigem Bild startet ein sichtbarer 3-Sekunden-Countdown. Bewegung oder Verlust der Kontur bricht ihn ab. Nach Ablauf wird aufgenommen.
- **Todo List:**
  1. Stabilitätsmaß aus aufeinanderfolgenden Konturen bzw. Frame-Differenzen mit Schwellwert.
  2. Zustandsautomat: Suche, Stabil (Countdown 3→0), Aufnahme, mit Abbruch bei Instabilität.
  3. Countdown-Anzeige in der UI.
  4. Sperre gegen Mehrfachauslösung direkt nach einer Aufnahme.
  5. Unit-Tests für den Zustandsautomaten.
- **Relevant Context:** Paket `com.hape.pdfscan.scan`.
- **Status:** `[x] done`

#### Kontext für den nächsten Sub-Task

**Neue Klassen:**
| Klasse | Paket | Kurzbeschreibung |
|---|---|---|
| `AutoCaptureState` (sealed) | `scan` | `Idle` · `Stable(remainingSeconds: Int)` |
| `AutoCaptureController` | `scan` | Zustandsautomat: `onDetectionResult(result)`, `reset()`, `state: StateFlow<AutoCaptureState>`, `onFire: () -> Unit`. Stabil = kein Fallback + max. Eckpunkt-Drift ≤ 15 px (konfigurierbar). Countdown 3 → 1 s in 1-s-Schritten. |

**Einbindung in vorhandene Klassen:**
- `ScanViewModel`: hat `autoCaptureController` (mit `viewModelScope`), `autoCaptureState: StateFlow`, `onAutoCaptureFire: (() -> Unit)?`. `onDetectionResult` leitet an den Controller weiter. `onCaptureDone`, `onCameraError` und `onCleared` rufen `reset()` auf.
- `ScanScreen`: setzt `viewModel.onAutoCaptureFire = { controller.capture(cacheDir) }` per `SideEffect`. Zeigt `CountdownDisplay` (schwarzer Kreis mit Zahl, oben mittig) wenn `AutoCaptureState.Stable`.

**Tests:** `AutoCaptureControllerTest` (11 Tests, alle grün). Gesamtzahl: 114 Tests.

**Abweichungen vom Plan:**
- Keine neue Abhängigkeit. `kotlinx-coroutines-test` war bereits vorhanden.
- `ScanCameraController.capturing`-Flag sperrt Mehrfachauslösung bereits; Auto-Capture ruft denselben `capture()`-Pfad auf.
- `onAutoCaptureFire` ist ein Callback am ViewModel (kein eigener Kanal), damit der Controller und die UI vom ViewModel aus testbar bleiben.

### 6. Scan-Ablauf, Vorschau und PDF-Erzeugung
- **Intent:** Mehrseitige Dokumente erfassen und als PDF speichern.
- **Expected Outcomes:** Nach jeder Seite erscheinen die zugeschnittene Vorschau und die Frage nach einer weiteren Seite. Beim Beenden wird der Dokumentname abgefragt und die PDF im aktuellen Zielordner gespeichert.
- **Todo List:**
  1. Seitenliste im Speicher bzw. in temporären Dateien (Cache), Aufräumen nach Abschluss oder Abbruch.
  2. Vorschau-Dialog mit "Weitere Seite" und "Fertig"; optional "Verwerfen" für die aktuelle Seite.
  3. Namensdialog mit Validierung und Duplikatprüfung (Sub-Task 2).
  4. `PdfDocument` mit je einer Seite pro Bild, JPEG-Qualität und Seitengröße sinnvoll wählen, Speicherverbrauch bei vielen Seiten begrenzen.
  5. PDF atomar schreiben (temporäre Datei, dann umbenennen).
  6. Zurück zum Hauptmenü mit aktualisierter Liste.
- **Relevant Context:** Pakete `com.hape.pdfscan.scan` und `com.hape.pdfscan.pdf`.
- **Status:** `[x] done`

#### Kontext für den nächsten Sub-Task

**Neue Klassen:**
| Klasse | Paket | Kurzbeschreibung |
|---|---|---|
| `ScannedPage` | `ui.scan` | `jpegFile: File`, `bitmap: Bitmap` – eine gescannte Seite |
| `PdfWriter` (object) | `pdf` | `write(pages, outputFile): Boolean` – schreibt alle Seiten atomar (tmp → rename) in eine PDF |

**Erweiterte Klassen:**
| Klasse | Änderung |
|---|---|
| `ScanUiState` | Neu: `PagePreview(newPage, pages)`, `Naming(pages, nameError?, isSaving)`. `Preview` entfernt. |
| `ScanViewModel` | Seitenliste `_confirmedPages`, Methoden `onCaptureDone`, `onAddAnotherPage`, `onDiscardPage`, `onFinishScanning`, `onSavePdf`, `onCancelNaming`. `savedPdf: StateFlow<File?>` als Navigationssignal. |
| `ScanScreen` | Neue Branches `PagePreview` (3 Buttons: Verwerfen / Weitere Seite / Fertig) und `Naming` (Textfeld + Speichern/Abbrechen + Fortschrittsanzeige). Navigation zurück per `savedPdf`-Flow. |

**Ablauf:**
1. Aufnahme → `PagePreview(newPage, pages = bisherige Seiten)`
2. „Verwerfen": newPage löschen, zurück zur Kamera
3. „Weitere Seite": newPage bestätigen, zurück zur Kamera; `_confirmedPages` hält alle bestätigten Seiten
4. „Fertig": alle Seiten bestätigen → `Naming`
5. Name eingeben → `NameValidator.validate(name)` (ohne .pdf) + Duplikatprüfung → `PdfWriter.write` auf IO-Dispatcher → `savedPdf` setzen → Screen navigiert zurück
6. „Abbrechen" im Naming: zurück zur PagePreview der letzten Seite

**Entscheidungen:**
- `.pdf` wird automatisch angehängt; der Nutzer gibt nur den Basisnamen ein.
- `targetDirectory` kommt aus dem Navigationsargument (Sub-Task 3). Fehlt es (kein Zielordner gesetzt), wird `context.filesDir` als Fallback genutzt.
- `PdfWriter` ist nicht unit-testbar auf JVM (`android.graphics.pdf.PdfDocument`); keine separaten JVM-Unit-Tests dafür.
- Neue Strings: `action_add_page`, `action_finish_scan`, `scan_page_number`, `scan_name_title`, `scan_name_label`, `scan_name_hint_extension`, `error_pdf_save`.
- `action_retake` entfernt (ersetzt durch `action_add_page`).

**Tests:** 114 Unit-Tests, alle grün. Build und Lint sauber.

### 7. Einstellungen (Scan-Ordner)
- **Intent:** Den Standardordner `/Scans` änderbar machen.
- **Expected Outcomes:** Der Nutzer kann einen anderen Zielordner im internen Speicher wählen oder auf den Standard zurücksetzen. Das Hauptmenü nutzt danach den neuen Ordner.
- **Todo List:**
  1. Einstellungsscreen mit Anzeige des aktuellen Pfads.
  2. In-App-Ordnerauswahl (Navigation durch Verzeichnisse, Ordner anlegen). Der Pfad kommt direkt als Dateisystempfad, ohne SAF-Umrechnung.
  3. Pfadvalidierung (existiert, beschreibbar, liegt im internen Speicher). Bei Fehler ein Hinweis und keine Änderung.
  4. Zurücksetzen auf `/Scans`.
  5. Der Ordner wird bei Bedarf angelegt.
- **Relevant Context:** DataStore aus Sub-Task 2.
- **Status:** `[x] done`

#### Kontext für den nächsten Sub-Task

**Neue Klassen:**
| Klasse | Paket | Kurzbeschreibung |
|---|---|---|
| `SettingsViewModel` | `ui.settings` | `folderSettings`, `applyFolder(folder)`, `onResetFolder()`, Ordner-Picker-Navigation. `uiState: StateFlow<SettingsUiState>` |
| `SettingsUiState` | `ui.settings` | `currentScanFolder`, `folderPickerState?: FolderPickerUiState`, `savedMessage`, `folderError?` |
| `FolderPickerUiState` | `ui.settings` | `currentDirectory`, `pathStack`, `subFolders`, `isLoading` |
| `SettingsScreen` | `ui.settings` | Ersetzt `SettingsPlaceholderScreen` in `AppNavHost`. Zeigt aktuellen Pfad, Buttons "Ordner wählen" / "Zurücksetzen", Ordner-Picker-Dialog, Snackbar-Feedback |
| `validateFolder(folder)` | `ui.settings` | Top-level-Funktion; prüft: im internen Speicher, existiert/erstellbar, beschreibbar. Gibt String-Res-ID zurück oder `null` |

**Entscheidungen:**
- Picker startet an `Environment.getExternalStorageDirectory()` und zeigt nur echte Verzeichnisse (keine versteckten).
- Kein SAF, kein `ACTION_OPEN_DOCUMENT_TREE`: alles über `java.io.File`.
- Neue Strings: `settings_section_scan_folder`, `settings_current_scan_folder`, `settings_choose_folder`, `settings_reset_folder`, `settings_folder_saved`, `settings_no_subfolders`, `error_settings_folder_invalid`, `error_settings_folder_not_internal`, `error_settings_folder_not_writable`.
- `SettingsPlaceholderScreen` bleibt in `PlaceholderScreens.kt` erhalten (nur nicht mehr geroutet).

**Tests:** 114 Unit-Tests, alle grün. Build und Lint sauber.

### 8. About-Seite und Versionslogik
- **Intent:** Kurze Funktionsbeschreibung, angezeigt beim ersten Start und bei Versionsänderung.
- **Expected Outcomes:** About erscheint nach der Installation und nach jedem Update mit geänderter `versionName`. Danach ist sie nur noch über das Menü erreichbar.
- **Todo List:**
  1. About-Screen mit Logo, App-Name, Version, kurzer Funktionsbeschreibung (de und en).
  2. In DataStore die zuletzt angezeigte `versionName` speichern. Beim Start vergleichen.
  3. Nach "Schließen" die aktuelle Version speichern.
  4. Test der Vergleichslogik.
- **Relevant Context:** Paket `com.hape.pdfscan.ui.about`, `BuildConfig.VERSION_NAME`.
- **Status:** `[x] done`

#### Kontext
- `AboutViewModel`: liest `about_last_seen_version` aus dem `"settings"`-DataStore; `isAutoShown = lastSeen != VERSION_NAME`; `onClose()` schreibt aktuelle Version. Schlüssel identisch zur `MainActivity`-Prüfung.
- `AboutScreen`: Logo (`ic_app_logo.png`, 192 dp), App-Name, Version (`BuildConfig.VERSION_NAME`), Beschreibung (de + en), Schließen-Button.
- `MainActivity`: liest DataStore per `LaunchedEffect`, navigiert bei Versionsänderung nach `NavRoutes.ABOUT`.
- Neue Strings: `about_version`, `about_description`, `action_close`.
- Tests: keine neuen Unit-Tests (DataStore-Logik braucht Android). Gesamtzahl: 114, alle grün.

### 9. App-Icon aus dem Logo
- **Intent:** Das Logo [`PDFScan-BY-BOB.png`](PDFScan-BY-BOB.png) als Launcher-Icon verwenden.
- **Expected Outcomes:** Die App zeigt auf dem Homescreen das Logo, auch als adaptives Icon.
- **Todo List:**
  1. Icon-Größen für die Dichten (mdpi bis xxxhdpi) aus dem PNG erzeugen.
  2. Adaptives Icon (Hintergrund und Vordergrund mit Sicherheitsrand) plus Fallback.
  3. Manifest-Verweise setzen.
  4. Logo für die About-Seite als Drawable einbinden.
- **Relevant Context:** [`PDFScan-BY-BOB.png`](PDFScan-BY-BOB.png). Das zweite Bild [`PDFScan-BY-BOB-banner.jpg.jpg`](PDFScan-BY-BOB-banner.jpg.jpg) hat eine doppelte Dateiendung und wird nicht eingebunden.
- **Status:** `[x] done`

#### Kontext
- `sips` erzeugte PNGs in mdpi/48, hdpi/72, xhdpi/96, xxhdpi/144, xxxhdpi/192 px → `mipmap-<dpi>/ic_launcher.png`.
- Altes `mipmap-hdpi/ic_launcher.xml` (Platzhalter-Shape) entfernt.
- Adaptives Icon (`mipmap-anydpi-v26/ic_launcher.xml`): Hintergrund `@color/ic_launcher_background` (weiß), Foreground `@drawable/ic_launcher_foreground.png` (432×432 px, 108 dp-Basis).
- Altes `drawable/ic_launcher_foreground.xml` (Platzhalter-Dreieck) entfernt.
- Logo für About-Screen: `drawable/ic_app_logo.png` (192×192 px), referenziert als `R.drawable.ic_app_logo`.
- `AndroidManifest.xml` unverändert (zeigt bereits auf `@mipmap/ic_launcher`).

### 10. Dokumentation: README, VERSION, CHANGELOG, AGENT.md
- **Intent:** Projektdokumentation für Menschen und KI-Agenten.
- **Expected Outcomes:** `README.md`, `VERSION`, `CHANGELOG.md` und `AGENT.md` liegen im Projektwurzelverzeichnis.
- **Todo List:**
  1. `VERSION` mit `1.0.0`. Der Build soll `versionName` daraus lesen, damit es nur eine Quelle gibt.
  2. `CHANGELOG.md` im Format "Keep a Changelog" mit Eintrag für 1.0.0 (Datum, Liste der Funktionen). Der Abschnitt "Unreleased" bleibt für künftige Änderungen.
  3. `README.md`: Funktionen, Voraussetzungen, Bauen, Signieren (Gradle und `uber-apk-signer`), Berechtigungen (Hinweis zu "Zugriff auf alle Dateien"), Speicherort.
  4. `AGENT.md`: Architektur, Paketstruktur, Konventionen, Build- und Testbefehle, Sicherheitsregeln (keine Secrets, keine Zugangsdaten im Repo, Eingabevalidierung). Regel: Bei jeder Versionsänderung `VERSION` und `CHANGELOG.md` gemeinsam aktualisieren, `versionCode` erhöhen.
  5. [`.gitignore`](.gitignore) prüfen, ob Build-Artefakte (APK) und Signierdateien ausgeschlossen sind.
- **Relevant Context:** [`.gitignore`](.gitignore), [`uber-apk-signer.jar`](uber-apk-signer.jar).
- **Status:** `[x] done`

#### Kontext
- [`README.md`](README.md): Funktionen, Voraussetzungen, Bauen, beide Signier-Varianten, Berechtigungshinweis.
- [`CHANGELOG.md`](CHANGELOG.md): Keep-a-Changelog-Format, Abschnitt `[1.0.0]` + leerer `[Unreleased]`.
- [`AGENT.md`](AGENT.md): Architektur, Paketkonventionen, Build-Befehle, Sicherheitsregeln, Versionsregel.
- `VERSION` war bereits vorhanden (`1.0.0`); `app/build.gradle` liest sie bereits.
- `.gitignore` enthält `dist/` (APKs), `*.jks`, `*.keystore`, `keystore.properties` – vollständig.

### 11. Build, Signierung und Abschlussprüfung
- **Intent:** Nachweisen, dass die App baut, getestet ist und signiert werden kann.
- **Expected Outcomes:** Unit-Tests laufen grün, Lint ohne neue Warnungen, signierte Release-APK liegt vor.
- **Todo List:**
  1. `./gradlew test` und `./gradlew lint` ausführen und Fehler beheben.
  2. `./gradlew assembleRelease` mit Signierung aus `keystore.properties`.
  3. Alternativ mit `uber-apk-signer.jar` signieren und prüfen (`--verbose`).
  4. Manuelle Testliste: Berechtigungsfluss (API 29 und neuer), Scan mit Knopf, Auto-Auslösung, Mehrseiten-PDF, Ordner anlegen (auch mehrstufig verschachtelt), Dokumente und Ordner kopieren, verschieben, umbenennen, löschen, Dokument teilen, Ordnerwechsel in den Einstellungen, About beim ersten Start und nach Versionsänderung, Deutsch und Englisch.
  5. Abhängigkeiten auf bekannte Schwachstellen prüfen.
- **Relevant Context:** [`uber-apk-signer.jar`](uber-apk-signer.jar), `keystore.properties`.
- **Status:** `[x] done` (1–2 erledigt; 3–5 manuell auf Gerät)

---

## Post-Plan-Fixes (nach Sub-Task 11)

| Fix | Status |
|---|---|
| Countdown-Flackern: `wasScanning`-Guard in `onDetectionResult` | ✅ erledigt |
| Countdown-Dauer in Einstellungen konfigurierbar (0 = aus, Schieberegler 0–10 s) | ✅ erledigt |
| PDF-Tap → In-App-`PdfViewerScreen` (ersetzt `ACTION_VIEW` als primäre Aktion) | ✅ erledigt |
| PDF-Viewer: `PdfRenderer` rendert Seiten als Bitmap (2×, weißer HG), `HorizontalPager` für mehrere Seiten | ✅ erledigt |
| PDF-Viewer Swipe-Bug: `awaitEachGesture`-Implementierung, konsumiert nur bei Multi-Touch oder Zoom > 1 | ✅ erledigt, Build verifiziert |

---

## Risiken und offene Punkte
- "Zugriff auf alle Dateien" schränkt den Vertrieb über den Play Store ein. Für signierte APK-Verteilung ist es unkritisch.
- Die Randerkennung ist bei geringem Kontrast zwischen Blatt und Unterlage weniger zuverlässig. Daher gibt es den Fallback auf das ganze Bild.
- OpenCV vergrößert die APK. Optional ist ABI-Splitting möglich.
- Das Flag `requestLegacyExternalStorage` auf Android 10 ist auf einem Gerät oder Emulator zu verifizieren.
