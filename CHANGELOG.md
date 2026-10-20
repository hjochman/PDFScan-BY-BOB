# Changelog

Alle relevanten Änderungen werden in dieser Datei dokumentiert.
Format: [Keep a Changelog](https://keepachangelog.com/de/1.1.0/)
Versionierung: [Semantic Versioning](https://semver.org/lang/de/)

## [Unreleased]

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
