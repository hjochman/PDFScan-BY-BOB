# AGENT.md – PDFScan by Bob

Dieser Leitfaden beschreibt Architektur, Konventionen und Regeln für KI-Agenten, die an diesem Projekt arbeiten.

## Architektur

```
com.hape.pdfscan
├── MainActivity.kt              – Einstiegspunkt, prüft About-Auto-Show
├── navigation/
│   ├── AppNavHost.kt            – Compose NavHost, alle Routen
│   └── NavRoutes.kt             – Routendefinitionen + URL-Encoding
├── scan/                        – Reine Logik (kein Android/UI außer Bitmap)
│   ├── DocumentCorners.kt       – Datenklasse: 4 Ecken + FloatPoint
│   ├── DocumentDetector.kt      – OpenCV-Kantenerkennung + Zuschnitt
│   ├── DocumentGeometry.kt      – Reine Geometrie (JVM-testbar)
│   └── AutoCaptureController.kt – Stabilitäts-Zustandsautomat (JVM-testbar)
├── pdf/
│   └── PdfWriter.kt             – Atomares Schreiben einer mehrseitigen PDF
├── storage/
│   ├── NameValidator.kt         – Allowlist-Validierung von Dateinamen
│   ├── PathGuard.kt             – Kanonische Pfadprüfung
│   ├── ScanFileRepository.kt    – Dateisystem-CRUD (suspend, StorageResult)
│   ├── ScanFolderSettings.kt    – DataStore: Scan-Ordner-Pfad
│   ├── StoragePermission.kt     – Berechtigungs-Helfer (API 29 + 30+)
│   └── StorageResult.kt         – StorageResult, StorageError, ScanEntry
└── ui/
    ├── theme/Theme.kt
    ├── main/                    – Hauptmenü (Liste, Dialoge, ViewModel)
    ├── scan/                    – Kamera-Screen, ScanViewModel
    ├── settings/                – Einstellungs-Screen, SettingsViewModel
    ├── about/                   – About-Screen, AboutViewModel
    └── placeholder/             – Nicht mehr geroutete Placeholder (Altcode)
```

## Paketkonventionen

- **`scan/`** – nur reine Logik; kein `Context`, kein `ViewModel`. OpenCV darf hier vorkommen.
- **`storage/`** – kein UI. Alle Operationen liefern `StorageResult<T>`.
- **`ui/*/ViewModel`** – hält `StateFlow`, ruft Repository/Settings auf, keine direkten View-Referenzen.
- **`ui/*/Screen`** – nur Compose. Kein Business-Logik-Code.
- **Fehler:** Keine rohen Exception-Meldungen in die UI. Logs nur mit Exception-Klasse, ohne Pfade/Namen.

## Build- und Testbefehle

```bash
# Alle Unit-Tests
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew test

# Debug-APK
./gradlew assembleDebug

# Release-APK (braucht keystore.properties)
./gradlew assembleRelease

# Lint
./gradlew lint

# Alles auf einmal
./gradlew test assembleDebug lint
```

## Sicherheitsregeln

- **Keine Secrets im Code oder Git.** Schlüssel kommen aus `keystore.properties` (git-ignoriert).
- **Keine Pfad-Traversal.** Alle Pfade werden per `canonicalFile` aufgelöst und durch `PathGuard` auf den Scan-Ordner beschränkt.
- **Eingabevalidierung:** Dateinamen werden durch `NameValidator.validate()` geprüft (Allowlist).
- **Kein Überschreiben:** `ScanFileRepository` überschreibt nie bestehende Dateien.

## Versionsregel

Bei jeder Versionsänderung:
1. `VERSION` (Projektwurzel) mit neuer `versionName` aktualisieren.
2. `CHANGELOG.md` – neuen Abschnitt unter `[Unreleased]` einfügen.
3. `versionCode` in `app/build.gradle` erhöhen.
4. `VERSION`-Datei ist die einzige Quelle; `app/build.gradle` liest sie automatisch.
5. Nach dem Build die APKs mit Versionsname ins `dist/`-Verzeichnis kopieren:

```bash
VERSION=$(cat VERSION)
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk     "dist/PDFScan-by-Bob-${VERSION}-debug.apk"
cp app/build/outputs/apk/release/app-release.apk "dist/PDFScan-by-Bob-${VERSION}.apk"
cp app/build/outputs/apk/release/app-release.apk "dist/PDFScan-by-Bob-${VERSION}-release.apk"
```

Der Ordner `dist/` und `PDFScan-by-Bob-*.apk` sind in `.gitignore` eingetragen (nicht committen).

## Abhängigkeiten

Immer die neueste stabile, gepflegte Version verwenden. Versionen in `app/build.gradle` prüfen bevor neue Abhängigkeiten eingetragen werden. Keine EOL-Pakete, keine Pakete ohne Commit in den letzten 6 Monaten.

## Code-Landkarte (Stand der Analyse, damit nicht neu gesucht werden muss)

Plattform: Android, Kotlin, Jetpack Compose, minSdk 29, targetSdk 35. Strings in `res/values/strings.xml` (de) und `res/values-en/strings.xml` (en) – beide pflegen.

| Thema | Datei / Stelle |
|---|---|
| PDF schreiben | `pdf/PdfWriter.kt` – `write(jpegFiles, outputFile)`, eigener PDF-1.4-Writer, atomar (tmp, dann rename) |
| JPEG-Qualität / Auflösung | `ui/scan/ScanCameraController.kt` – `processCapture()` liest `viewModel.jpegQuality` und `viewModel.maxDimension` (aus `ScanFolderSettings`) |
| PDF-Speichern | `ui/scan/ScanViewModel.kt` – `onSavePdf()` |
| Beschnitt / Zielgröße | `scan/DocumentGeometry.kt` – `targetSize()`; `scan/DocumentDetector.kt` – `cropMat()` |
| Kantenerkennung | `scan/DocumentDetector.kt` – `detect()`, Stufe 1 (Canny) und Stufe 2 (robust). Analyse-Frame max. 1000 px lange Kante |
| Stabilität / Auto-Auslöser | `scan/AutoCaptureController.kt` – Drift max. 15 px, Tick 1 s. Eckenglättung: `scan/CornerSmoother.kt` (aufgerufen in `ScanViewModel.onDetectionResult`, Stärke 0..10 aus `ScanFolderSettings.smoothingStrength`, Regler in `CameraPreviewContent`) |
| Live-Overlay | `ui/scan/ScanScreen.kt` – `DocumentOverlay`, `CameraPreviewContent`, `PagePreviewContent` |
| Kamera | `ui/scan/ScanCameraController.kt` – CameraX, Analyse 1280x720 |
| Einstellungen speichern | `storage/ScanFolderSettings.kt` – DataStore `settings`, Muster: Key, Flow, Setter (siehe `countdownSeconds`) |
| Einstellungen UI | `ui/settings/SettingsScreen.kt`, `SettingsViewModel.kt` |
| Viewer | `ui/viewer/PdfViewerScreen.kt` (`HorizontalPager`, `ZoomablePage`), `PdfViewerViewModel.kt` (`PdfRenderer`) |
| About | `ui/about/AboutScreen.kt`, `AboutViewModel.kt` |
| Tests (JVM) | `app/src/test/java/com/hape/pdfscan/` – `scan/`, `storage/`, `ui/main/` |

## Bekannte Auffälligkeiten (Feldtest) und Plan

Detailplan mit Sub-Tasks: [`improvements-plan.md`](improvements-plan.md).

1. PDFs zu groß: Behoben. `PdfWriter` bettet die Cache-JPGs jetzt unverändert als DCTDecode ein; Auflösung (1200–3000 px) und JPEG-Qualität (50–95 %) sind in den Settings einstellbar. Offen: Reale Dateigröße und Anzeige in anderen PDF-Programmen am Gerät prüfen.
2. Rand springt: Behoben. `scan/CornerSmoother.kt` glättet die Ecken exponentiell (Reset bei großem Sprung und `isFallback`), Regler "Randglättung" in der Scanansicht (Default 5, persistiert); Test `CornerSmootherTest`. Offen: Wirkung am Gerät prüfen, ggf. Schwelle/Default anpassen.
3. Viewer: Seitenzahl "x/y" in der `TopAppBar`. Behoben: `rememberPagerState` auf Screen-Ebene hochgezogen; Titel zeigt im Zustand `Ready` Dateiname + Seitenzahl ("x/y") in einer `Column`; Einzelseiten zeigen "1/1".
4. Lizenz: Behoben. `LICENSE` (GPL-3.0-or-later, offizieller GNU-Text), README-Abschnitt, Strings und About-Screen-Hinweis mit GitHub-Link eingefügt. Abhängigkeiten geprüft: alle Apache 2.0 / EPL – GPL-kompatibel.
5. Bilder gestaucht: `targetSize()` begrenzte Breite und Höhe unabhängig voneinander (`coerceIn`). Behoben (gemeinsamer Skalierungsfaktor, Tests ergänzt). Offen: Ob Overlay und Beschnitt am Gerät noch voneinander abweichen, zusammen mit Punkt 2 prüfen.

## Screenshots und Geräte-Debugging

**Regel: Bilder immer klein halten.** Volle PNG-Screenshots (rund 3,7 MB) nicht mit `read_file` öffnen. Zuerst verkleinern und als JPEG speichern, dann erst ansehen. Kein Bild ansehen, wenn Logcat-Zeilen die Frage beantworten, etwa bei der Kantenerkennung mit Eckpunkten und `stage`.

```bash
mkdir -p .bob/tmp
adb exec-out screencap -p > .bob/tmp/shot.png \
  && sips -Z 800 .bob/tmp/shot.png --setProperty format jpeg --setProperty formatOptions 60 --out .bob/tmp/shot.jpg \
  && rm .bob/tmp/shot.png
```

- Ergebnis: ca. 800 px Kantenlänge, meist unter 100 KB. Für einen Ausschnitt `sips --cropToHeightWidth <Höhe> <Breite>` verwenden.
- `read_file` darf nur Pfade im Workspace lesen. Deshalb liegen Screenshots in `.bob/tmp/` (nicht in `/tmp`). `.bob/` steht in `.gitignore`.
- `adb` liegt unter `/opt/homebrew/bin/adb`. Mit `adb devices -l` prüfen, ob das Gerät erreichbar ist. Es trennt sich gelegentlich.
- Debug- und Release-APK sind unterschiedlich signiert. Wechsel zwischen beiden scheitert mit `INSTALL_FAILED_UPDATE_INCOMPATIBLE` und braucht eine Deinstallation (App-Daten gehen dann verloren). Ob ein Downgrade (ältere über neuere Version) mit `adb install -r -d` klappt, ist nicht geprüft.
- `run-as` und die Diagnosezeile in der Scanansicht gibt es nur bei Debug-Builds.
- Bei Scan-Problemen zuerst Logcat prüfen: `adb logcat -d | grep -iE "pdfscan|opencv|AndroidRuntime"`. Logs dürfen keine Pfade oder Dateinamen enthalten (siehe Sicherheitsregeln).

## Arbeiten mit Subagents und Edits

- Subagents dürfen kein Gradle starten (Lauf blockiert sich). Build, Test und Lint macht der Hauptagent danach.
- Mehrere Edits an **derselben** Datei nacheinander ausführen, nicht parallel. Parallele Edits können sich überschreiben, beim CHANGELOG ist dadurch eine Änderung verloren gegangen. Das gilt auch für `strings.xml` (de und en), wenn mehrere Subagents daran arbeiten: pro Subagent nur eine kleine, eigene Zeilengruppe.

## Stand 1.1.0 (Erfolgreich getestet und freigegeben)

Version 1.1.0 ist fertiggestellt und auf dem Samsung Galaxy S21 Ultra getestet:
- **Kantenerkennung:** Stufe 1 & Stufe 2 nutzen `RETR_LIST`, Frame-Ausschluss (90 % Limit) und Eckpunktextraktion für komplexe Hintergründe.
- **Perspektivkorrektur:** 3D-Homographie (`estimateAspectRatio`) verhindert Stauchung/Verzerrung bei geneigten Aufnahmen.
- **Manueller Zuschnitt:** Interaktiver Drag-Editor in der Seitenvorschau (`ScanScreen.kt`) zum Feinjustieren der Eckpunkte.
- **About-Screen:** Aktualisiert mit den Neuerungen für v1.1.0.

## Wichtige Entscheidungen (Einmalig festgelegt)

| Thema | Entscheidung |
|---|---|
| Paketname | `com.hape.pdfscan` |
| minSdk | 29 |
| ABI | `arm64-v8a`, `armeabi-v7a` (kein Emulator-Support) |
| Speicher | `java.io.File` direkt, kein SAF |
| PDF | Eigener minimaler Writer (`pdf/PdfWriter.kt`), JPEG als `/DCTDecode`; Anzeige per `android.graphics.pdf.PdfRenderer` |
| OpenCV | Maven-AAR `org.opencv:opencv:5.0.0` |
| Signierung | `keystore.properties` + `patch-key.jks` (beide git-ignoriert) |
| Distribution | Signierte APK (kein Play Store) |
