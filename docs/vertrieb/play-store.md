# Google Play: Store-Eintrag und Formulare

## Store-Eintrag (Deutsch)

**App-Name (max. 30):** Protokoll KI – Offline-Protokoll

**Kurzbeschreibung (max. 80):** Besprechungen offline protokollieren: Sprecher, Beschlüsse, Aufgaben. Ohne Cloud.

**Vollständige Beschreibung:**
Protokoll KI schreibt Ihre Besprechungen mit – vollständig auf Ihrem Handy. Keine Cloud, kein Konto, keine Daten verlassen das Gerät. Ideal für Teams, soziale Einrichtungen, Pflege, Vereine und alle, die vertrauliche Gespräche dokumentieren.

So funktioniert's
• Aufnehmen oder Audiodatei importieren
• Die KI erkennt, wer wann spricht
• Sie erhalten ein Ergebnisprotokoll mit Themen, Beschlüssen und offenen Punkten – oder ein Wortprotokoll
• Aufgaben werden automatisch erkannt: wer macht was bis wann

Funktionen
• Formale Vorlagen: Dienst- und Fallbesprechung, Übergabe, Vereinssitzung mit Abstimmungen
• Tagesordnung vorab eingeben, das Protokoll folgt den TOPs
• Jeder Protokollpunkt springt zur passenden Stelle in der Aufnahme
• Fragen an alle Besprechungen stellen, mit Quellen
• Stimmprofile: bekannte Stimmen automatisch mit Namen
• Eigenes Wörterbuch für Namen und Fachbegriffe
• Export als PDF, Word, Markdown oder Text, auf Wunsch anonymisiert
• Datenschutz-Paket: App-Sperre, Screenshot-Schutz, Löschfristen
• Zusammenarbeit mit dem PC-Programm für Windows und Linux

Datenschutz
Spracherkennung, Sprechererkennung und Protokoll-KI laufen auf Ihrem Gerät. Das Internet wird nur einmal für den Download der KI-Modelle (1,5–5,6 GB je nach Handy) benötigt. Danach funktioniert die App auch im Flugmodus. Da wir keinen Zugriff auf Ihre Daten haben, brauchen Sie keinen Auftragsverarbeitungsvertrag.

Hinweis: Bitte holen Sie vor jeder Aufnahme das Einverständnis aller Anwesenden ein. KI-Protokolle können Fehler enthalten.

**Kategorie:** Produktivität · **Tags:** Protokoll, Besprechung, Transkription, Diktat
**Kontakt-E-Mail:** (Support-Adresse) · **Datenschutzerklärung-URL:** (öffentliche Seite, z. B. GitHub Pages)

## Grafiken (fehlen noch)
- App-Symbol 512 × 512 PNG
- Vorstellungsgrafik 1024 × 500
- mindestens 2, besser 6–8 Handy-Screenshots (z. B. Start, Aufnahme, Protokoll, Aufgaben, Quellenangabe, Datenschutz)

## Datensicherheit (Data Safety)
| Frage | Antwort |
|---|---|
| Erhebt oder teilt die App Nutzerdaten? | **Nein.** Audio, Transkripte usw. werden nur auf dem Gerät verarbeitet („on-device processing“ zählt laut Google nicht als Erhebung) |
| Werden Daten bei der Übertragung verschlüsselt? | Ja (Modell-Download per HTTPS) |
| Können Nutzer das Löschen ihrer Daten anfordern? | Daten liegen nur lokal; Löschen in der App oder durch Deinstallation |
| Absturzberichte | Werden nur vom Nutzer selbst per E-Mail verschickt, nicht von der App übertragen → keine Angabe nötig |

## Berechtigungen und Vordergrunddienste
| Berechtigung | Begründung für die Play Console |
|---|---|
| RECORD_AUDIO | Kernfunktion: Besprechungen aufnehmen |
| FOREGROUND_SERVICE_MICROPHONE | Aufnahme läuft weiter bei ausgeschaltetem Bildschirm; der Nutzer startet und beendet sie selbst, Benachrichtigung sichtbar |
| FOREGROUND_SERVICE_DATA_SYNC | Einmaliger Download der KI-Modelle (mehrere GB) und lokale Verarbeitung einer Aufnahme, die mehrere Minuten dauert. Video der Funktion bereitstellen |
| USE_BIOMETRIC | optionale App-Sperre |
| INTERNET | nur Modell-Download |

Hinweis für später: Ab Android 15 sind dataSync-Dienste auf 6 Stunden pro Tag begrenzt. Für die Verarbeitung passt der Typ `mediaProcessing` (Android 15+) fachlich besser; Umstellung ist eine kleine Änderung, sobald Google bei der Prüfung nachfragt.

## Altersfreigabe (IARC-Fragebogen)
Keine Gewalt, keine Nutzerinteraktion mit Fremden, keine Käufe digitaler Güter außer der App selbst → voraussichtlich „USK ab 0“.

## Zielgruppe
Erwachsene (18+), keine Kinder-App.
