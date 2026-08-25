# AoO KB Scanner

Android-Prototyp zum lokalen Erfassen von Kampfberichten aus **Age of Origins**. Die App liest eine vom Nutzer freigegebene Bildschirmübertragung in kurzen Abständen, erkennt deutsche Berichtsfelder mit gebündeltem ML-Kit-OCR und führt überlappende Scroll-Ansichten in einer lokalen SQLite-Datenbank zusammen.

## Funktionsumfang

- Startet über die Android-Bildschirmfreigabe (`MediaProjection`); es wird **keine Videodatei gespeichert**.
- Erkennt Nachrichtenliste, Schlachtbericht-Übersicht und „Armee Info“ für Angreifer/Verteidiger.
- Erfasst Teilnehmer, Koordinaten, Gesamtwerte, Einheitenzeilen und Technologieboni.
- Zeigt ein durchklickbares Overlay: Grün erkannt, Gelb noch offen, Rot unplausibel.
- Erkennt zunächst den Schlachtbericht und fragt im Overlay nach dem Start. Erst mit „Scan beenden“ wird die Sitzung als genau ein Bericht abgeschlossen.
- Vergibt IDs im Format `KB-JJJJMMTT-HHMM-XXXXXXXX` und führt alle wiederholten/überlappenden Scroll-Bilder zwischen Start und Ende zusammen.
- Speichert alles nur lokal. Die App hat absichtlich keine Internet-Berechtigung.
- Ordnet Einheitensymbole über einen Bild-Fingerabdruck zu. Name und Kategorie können unter „Einheitennamen konfigurieren“ korrigiert werden.
- Liest die Stufe direkt als römische Zahl am Einheitensymbol und erlaubt eine feste Stufen-Zuordnung, falls OCR sie verfehlt.
- Namen und OCR-Aliase aller Technologie-/Statuswerte sind konfigurierbar.
- Speichert während des bestätigten Scans veränderte Ansichten als markierte Belegbilder. Die Scan-Prüfung verbindet sie vertikal zu einem Dokument und erlaubt Korrekturen sowie Ergänzungen einzelner Spieler-, Einheiten- und Statuswerte.
- Berichte verwenden kopierbare Tabellen und sind über Androids Dateiauswahl samt Scan-Dokument als mehrseitige PDF speicherbar.
- Optionaler Battle-Frenzy-Modus zeigt Punkte live im Overlay und im fertigen Bericht. Ressourcenfeld-Kämpfe können mit 50 % gewertet werden.

## Benutzung

1. Debug-APK installieren. Android warnt bei der Installation aus unbekannter Quelle; dies muss für die verwendete Datei-App bzw. den Browser erlaubt werden.
2. In der Scanner-App „Scanner starten & Spiel öffnen“ wählen.
3. Die Berechtigung „Über anderen Apps einblenden“ und die Android-Bildschirmfreigabe bestätigen.
4. Die Schlachtbericht-Übersicht öffnen und oben rechts „Scan starten“ drücken.
5. Die Details **jedes Spielers beider Seiten langsam** von oben bis zum Ende der Technologieboni scrollen.
6. Nach dem letzten Feld „Scan beenden“ drücken. Erst dann wird die eine zusammengeführte Sitzung abgeschlossen.
7. Unter „Scan-Dokument prüfen & Werte korrigieren“ fehlende/fehlerhafte Werte kontrollieren und bei Bedarf ergänzen.
8. Der tabellarische Bericht lässt sich kopieren oder samt markierter Belegbilder als PDF speichern.

## Battle-Frenzy-Punkte

Gewertet werden verwundete plus gefallene Einheiten der Gegenseite. Pro Einheit gilt:

| Stufe | Angreifer | Verteidiger |
|---|---:|---:|
| T1–T4 | 0 | 0 |
| T5 | 2 | 1 |
| T6 | 4 | 2 |
| T7 | 7 | 3 |
| T8 | 14 | 5 |
| T9 | 24 | 8 |
| T10 | 39 | 13 |
| T11 | 50 | 16 |
| T12 | 65 | 20 |
| T13 | 80 | 25 |
| Titan | 6.000 | 2.000 |
| Kampfflugzeug | 6.000 | 2.000 |

Spezialeinheiten werden beim ersten Auftreten unter „Einheitennamen konfigurieren“ als Titan oder Kampfflugzeug markiert. Bei Ressourcenfeldern wird die Gesamtsumme halbiert und bei ungeraden Werten abgerundet.

Auf Huawei/EMUI kann es nötig sein, für den Scanner unter Akku/App-Start den automatischen Start und die Hintergrundausführung zu erlauben. Beim Drehen des Geräts sollte die laufende Erfassung neu gestartet werden.

## Datenschutz und Grenzen

Die OCR läuft offline auf dem Gerät. Nur zwischen „Scan starten“ und „Scan beenden“ werden maximal 36 deutlich veränderte, komprimierte Belegbilder im privaten App-Speicher abgelegt; sie verlassen das Gerät nicht. Diese Alpha-Version ist zunächst auf die deutsche Oberfläche und das in den Testaufnahmen gezeigte Hochformat optimiert. Spiel-Updates, andere Display-Seitenverhältnisse und OCR-Fehler können Nachkalibrierung erfordern. Die App greift nicht in das Spiel ein und führt keine Klicks aus.

## Build

Jeder Push auf `main` startet `.github/workflows/android.yml`. Der Workflow führt Unit-Tests aus, baut `app-debug.apk` und veröffentlicht APK plus SHA-256 als 90 Tage verfügbares GitHub-Actions-Artefakt.

Lokal mit Java 17, Android SDK 35 und Gradle 8.9:

```bash
gradle clean testDebugUnitTest assembleDebug
```

Die Debug-APK wird von Androids Buildsystem automatisch mit einem temporären Debug-Schlüssel signiert. Eine vollständig unsignierte APK kann Android nicht installieren; produktive Release-Signierung ist noch nicht eingerichtet.
