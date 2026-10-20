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
5. Nach dem Build die APKs mit Versionsname ins Projektwurzelverzeichnis kopieren:

```bash
VERSION=$(cat VERSION)
cp app/build/outputs/apk/debug/app-debug.apk     "PDFScan-by-Bob-${VERSION}-debug.apk"
cp app/build/outputs/apk/release/app-release.apk "PDFScan-by-Bob-${VERSION}-release.apk"
```

Die Dateien `PDFScan-by-Bob-*.apk` sind in `.gitignore` eingetragen (nicht committen).

## Abhängigkeiten

Immer die neueste stabile, gepflegte Version verwenden. Versionen in `app/build.gradle` prüfen bevor neue Abhängigkeiten eingetragen werden. Keine EOL-Pakete, keine Pakete ohne Commit in den letzten 6 Monaten.

## Wichtige Entscheidungen (Einmalig festgelegt)

| Thema | Entscheidung |
|---|---|
| Paketname | `com.hape.pdfscan` |
| minSdk | 29 |
| ABI | `arm64-v8a`, `armeabi-v7a` (kein Emulator-Support) |
| Speicher | `java.io.File` direkt, kein SAF |
| PDF | `android.graphics.pdf.PdfDocument` |
| OpenCV | Maven-AAR `org.opencv:opencv:5.0.0` |
| Signierung | `keystore.properties` + `patch-key.jks` (beide git-ignoriert) |
| Distribution | Signierte APK (kein Play Store) |
