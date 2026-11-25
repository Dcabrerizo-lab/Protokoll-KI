# Protokoll KI verkaufen: Checkliste

Stand: 25.11.2026. Legende: ✅ erledigt · 🟡 vorbereitet, David muss etwas tun · ❌ offen

## Recht und Lizenzen
| Punkt | Stand |
|---|---|
| Alle KI-Modelle kommerziell nutzbar | ✅ Paket „Standard“ nutzte Qwen2.5 3B (Qwen Research License, **nicht kommerziell**). Ersetzt durch Qwen3 4B Instruct 2507 (Apache-2.0). Details: [lizenzen-audit.md](lizenzen-audit.md) |
| Lizenzhinweise in App und PC-Programm | ✅ Einstellungen → Über → Open-Source-Lizenzen (Text: `app/src/main/assets/legal/lizenzen.md`) |
| Datenschutzerklärung | 🟡 Entwurf in App und PC-Programm. Verantwortlicher fehlt. Für Google Play zusätzlich als öffentliche Webseite nötig |
| Impressum | 🟡 Platzhalter in `app/src/main/assets/legal/impressum.md`. Die Verkaufsversion baut erst, wenn alles ausgefüllt ist |
| Hinweis Einwilligung bei Aufnahmen (§ 201 StGB) | ✅ im Onboarding und in der Datenschutzerklärung |
| KI-Kennzeichnung (EU AI Act Art. 50) | ✅ Exporte tragen „KI-gestützt, vor Weitergabe bitte prüfen“ |
| AGB / Endnutzer-Lizenzvertrag (EULA), Widerrufsbelehrung | ❌ nötig beim Direktverkauf (nicht bei Google Play). Am besten von einem Anwalt oder per Rechtstexte-Dienst (z. B. IT-Recht Kanzlei, Händlerbund, eRecht24) |
| Quelltext-Repository öffentlich | ❌ Entscheidung: Repo auf privat stellen? Siehe Fragen |

## Android / Google Play
| Punkt | Stand |
|---|---|
| Geheimer Release-Schlüssel | 🟡 Der bisherige Schlüssel liegt mit Passwort öffentlich im Repo. Jeder könnte damit eine gefälschte „Protokoll KI“ signieren. Neuer Ablauf `store.yml` nutzt einen geheimen Schlüssel aus GitHub-Secrets. Anleitung: [signatur.md](signatur.md) |
| App Bundle (AAB) für Play | ✅ `store.yml` baut `ProtokollKI.aab` (Aktionen → „Verkaufsversion bauen“ oder Tag `v1.0.0`) |
| targetSdk aktuell | ✅ 36 (Pflicht für neue Apps: 35) |
| Versionsnummern | ✅ Verkaufsversion zählt ab 100000, Name aus dem Tag |
| Play-Konto | ❌ Entwicklerkonto (25 US-Dollar einmalig). Neue Privatkonten müssen 14 Tage mit 12 Testern testen; Firmenkonto (D-U-N-S-Nummer) nicht |
| Store-Eintrag, Data Safety, Berechtigungen | 🟡 Texte fertig: [play-store.md](play-store.md). Screenshots und Grafik fehlen |
| Absturzberichte | ✅ lokal, freiwillig per E-Mail (auch native Abstürze und Hänger über Android 11+). Support-Adresse fehlt noch |

## PC (Windows / Linux)
| Punkt | Stand |
|---|---|
| Windows-Codesignatur | 🟡 `pc.yml` signiert automatisch, sobald Azure-Trusted-Signing-Secrets gesetzt sind. Ohne Signatur warnt SmartScreen („Unbekannter Herausgeber“) |
| Rechtstexte im Programm | ✅ Einstellungen → Datenschutz · Lizenzen · Impressum, im CI geprüft |
| Absturzprotokoll | ✅ unerwartete Fehler landen in `protokoll-ki.log` im Programmdatenordner |
| Lizenzschlüssel / Bezahlung | ❌ hängt vom Preismodell ab (siehe Fragen) |
| Verkaufsweg (Shop, Rechnung, Umsatzsteuer) | ❌ z. B. Paddle oder Lemon Squeezy (übernehmen EU-Umsatzsteuer als Händler), Digistore24, oder Microsoft Store |

## Offene Fragen an David
Siehe Nachricht im Projekt-Thread „Protokoll KI vertriebsfertig machen“.
