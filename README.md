# AoO KB Scanner

Android-Prototyp zum lokalen Erfassen von Kampfberichten aus **Age of Origins**. Die App liest eine vom Nutzer freigegebene Bildschirmübertragung in kurzen Abständen, erkennt deutsche Berichtsfelder mit gebündeltem ML-Kit-OCR und führt überlappende Scroll-Ansichten in einer lokalen SQLite-Datenbank zusammen.

## Funktionsumfang der ersten Version

- Startet über die Android-Bildschirmfreigabe (`MediaProjection`); es wird **keine Videodatei gespeichert**.
- Erkennt Nachrichtenliste, Schlachtbericht-Übersicht und „Armee Info“ für Angreifer/Verteidiger.
- Erfasst Teilnehmer, Koordinaten, Gesamtwerte, Einheitenzeilen und Technologieboni.
- Zeigt ein durchklickbares Overlay: Grün erkannt, Gelb noch offen, Rot unplausibel.
- Vergibt IDs im Format `KB-JJJJMMTT-HHMM-XXXXXXXX` und führt wiederholte/überlappende Bilder zusammen.
- Speichert alles nur lokal. Die App hat absichtlich keine Internet-Berechtigung.
- Ordnet Einheitensymbole über einen Bild-Fingerabdruck zu. Name und Kategorie können unter „Einheitennamen konfigurieren“ korrigiert werden.

## Benutzung

1. Debug-APK installieren. Android warnt bei der Installation aus unbekannter Quelle; dies muss für die verwendete Datei-App bzw. den Browser erlaubt werden.
2. In der Scanner-App „Scanner starten & Spiel öffnen“ wählen.
3. Die Berechtigung „Über anderen Apps einblenden“ und die Android-Bildschirmfreigabe bestätigen.
4. Einen Schlachtbericht öffnen und die Details **jedes Spielers beider Seiten langsam** von oben bis zum Ende der Technologieboni scrollen.
5. Oben rechts zeigt das Overlay den Erfassungsfortschritt. Gespeicherte Daten stehen in „Erfasste Berichte“.

Auf Huawei/EMUI kann es nötig sein, für den Scanner unter Akku/App-Start den automatischen Start und die Hintergrundausführung zu erlauben. Beim Drehen des Geräts sollte die laufende Erfassung neu gestartet werden.

## Datenschutz und Grenzen

Die OCR läuft offline auf dem Gerät. Bildschirmbilder werden nach der Verarbeitung aus dem Arbeitsspeicher freigegeben. Diese Alpha-Version ist zunächst auf die deutsche Oberfläche und das in den Testaufnahmen gezeigte Hochformat optimiert. Spiel-Updates, andere Display-Seitenverhältnisse und OCR-Fehler können Nachkalibrierung erfordern. Die App greift nicht in das Spiel ein und führt keine Klicks aus.

## Build

Jeder Push auf `main` startet `.github/workflows/android.yml`. Der Workflow führt Unit-Tests aus, baut `app-debug.apk` und veröffentlicht APK plus SHA-256 als 90 Tage verfügbares GitHub-Actions-Artefakt.

Lokal mit Java 17, Android SDK 35 und Gradle 8.9:

```bash
gradle clean testDebugUnitTest assembleDebug
```

Die Debug-APK wird von Androids Buildsystem automatisch mit einem temporären Debug-Schlüssel signiert. Eine vollständig unsignierte APK kann Android nicht installieren; produktive Release-Signierung ist noch nicht eingerichtet.
