# Changelog

Alle relevanten Änderungen werden in dieser Datei dokumentiert.
Format: [Keep a Changelog](https://keepachangelog.com/de/1.1.0/)
Versionierung: [Semantic Versioning](https://semver.org/lang/de/)

## [Unreleased]

## [1.1.0] – 2026-10-21

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
- Randglättung mit Regler in der Scanansicht
- Seitenzahl x/y in der TopAppBar des Viewers
- Lizenzhinweis (GPL-3.0-or-later, Copyright © 2025 hjochman) im About-Screen; `LICENSE`-Datei; GitHub-Link

## [1.0.0] – 2025-01-01

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
