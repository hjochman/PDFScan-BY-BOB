# Changelog

Alle relevanten Änderungen werden in dieser Datei dokumentiert.
Format: [Keep a Changelog](https://keepachangelog.com/de/1.1.0/)
Versionierung: [Semantic Versioning](https://semver.org/lang/de/)

## [1.1.1] - 2026-11-20

### Fixed
- Kantenerkennung auf unruhigem Untergrund (Stein, Holz, Muster) springt nicht mehr zwischen Flächen: `DocumentGeometry.quadScore` straft Vierecke mit Ecken am Bildrand ab (Faktor 0,5 je Ecke, Rand = 2 % der kürzeren Seite) und bewertet die Größe flacher (0,6 bis 1,0 statt 0,1 bis 1,0), damit ein Blatt von einem Viertel des Bildes nicht von einem Viereck über die ganze Szene geschlagen wird. Messung am Gerät: Sprünge pro 30 Frames von 23 bis 27 auf 0, mittlere Drift von 130 bis 200 px auf 1,8 px, Auto-Capture löst aus

### Changed
- Eckreihenfolge vereinheitlicht (Sub-Task 2, Punkt B von `edge-stability-plan.md`): `DocumentGeometry.orderCorners` ist die einzige Ordnungsfunktion (im Uhrzeigersinn um den Schwerpunkt, Start bei kleinster Summe x + y); neue `DocumentGeometry.cornersFromPoints` für 4 bis 12 Punkte, `DocumentDetector` nutzt sie statt einer eigenen Kopie. Gleiche Geometrie liefert damit auch bei gedrehten Vierecken (30° bis 60°) dieselbe Reihenfolge
- Auto-Capture-Driftschwelle relativ zur Bilddiagonale (Sub-Task 2, Punkt G): `AutoCaptureController.maxDriftPx` (15 px) ersetzt durch `maxDriftFraction` = 0,01 der Diagonale (14,7 px bei 1280x720), unabhängig von der Framegröße

### Added
- Messung der Kantenstabilität (Sub-Task 1 von `edge-stability-plan.md`, nur Diagnose, keine Änderung der Erkennung): `DetectionResult` liefert Gewinner-Maske, Score und Kandidatenzahl; neue Klasse `DetectionStats` zählt Sprünge, Aussetzer sowie mittlere und maximale Eckendrift über 30 Frames; Logcat-Tag `EdgeStats` (nur Debug-Builds, nur Zahlen) und erweiterte Diagnosezeile in der Scanansicht
- Kandidaten-Diagnose (nur Debug-Builds): `DetectionResult.topCandidates` mit den drei besten verschiedenen Vierecken (plus dem besten Viereck unter 50 % der Bildfläche); Logcat-Tag `EdgeCand` (nur Zahlen, Ecken als Bildanteile) und farbige Kandidatenlinien in der Scanvorschau (1 gelb, 2 rot, 3 grün). Die Linien sind in den Einstellungen unter „Entwickler (nur Debug-Build)“ ein- und ausschaltbar (Standard: aus; `ScanFolderSettings.showCandidateOverlay`); im Release-Build gibt es weder den Schalter noch die Linien


## [1.1.0] - 2026-10-21

- Randglättung mit Regler in der Scanansicht
- Seitenzahl x/y in der TopAppBar des Viewers
- Lizenzhinweis (GPL-3.0-or-later, Copyright © 2025 hjochman) im About-Screen; `LICENSE`-Datei; GitHub-Link## [1.1.0] – 2026-10-21

### Fixed
- Seitenverhältnis beim Beschnitt bleibt exakt erhalten: 3D-Perspektiv-Homographie (`estimateAspectRatio()`) und einheitliche Skalierung (`targetSize()`) verhindern Verzerrungen bei schrägen Aufnahmen
- Kantenerkennung in komplexen Umgebungen verbessert: Kontur-Rangliste, Frame-Ausschluss (90 % Limit) und Eckpunktextraktion für 5–12-Punkt-Hüllen
- Auto-Capture Countdown-Reset nach Auslösung behoben

### Changed
- PDFs betten JPEG-Bytes jetzt direkt ein (`/DCTDecode`) statt Bitmaps verlustfrei abzulegen

### Added
- Interaktiver manueller Zuschnitt: Ecken können nach der Aufnahme per Touch verschoben oder zurückgesetzt werden
- "Foto wird verarbeitet…"-Fortschrittsanzeige während der Aufnahmeverarbeitung
- Einstellungen für PDF-Auflösung (1200–3000 px) und JPEG-Qualität (50–95 %) in den Settings


## [1.0.0] – 2026-10-20

### Added
- Kamera-Scan mit Live-Kantenerkennung und Perspektivkorrektur (OpenCV 5, CameraX)
- Auto-Auslösung nach 3 Sekunden stabilem Bild mit sichtbarem Countdown
- Manueller Auslöser per Knopf
- Mehrseitiger Scan-Ablauf: beliebig viele Seiten pro Dokument
- PDF-Erzeugung (Android `PdfDocument`, atomar geschrieben)
- Dokumentenverwaltung: Ordner anlegen, umbenennen, löschen, kopieren, verschieben
- Teilen via `FileProvider` (`ACTION_SEND`)
- Einstellbarer Scan-Ordner (Standard `/Scans` im internen Speicher)
- About-Seite mit Logo und Versionsnummer; erscheint automatisch bei Erststart und Updates
- Sprachen: Deutsch (Standard), Englisch
- Unterstützung Android 10+ (API 29+), ARM64 und ARMv7
