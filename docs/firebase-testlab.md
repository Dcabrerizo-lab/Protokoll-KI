# Tests auf echten Handys einrichten (Firebase Test Lab)

Einmalig ca. 10 Minuten. Kostenlos (Spark-Tarif, **keine Zahlungsart nötig**): 5 Tests auf echten Handys pro Tag.

## 1. Firebase-Projekt anlegen
1. Öffne <https://console.firebase.google.com> und melde dich mit deinem Google-Konto an.
2. Klicke **Projekt erstellen** (bzw. „Firebase-Projekt erstellen“).
3. Name: `Protokoll KI` → **Weiter**.
4. Google Analytics **ausschalten** → **Projekt erstellen** → warten → **Weiter**.
5. Falls Firebase einen Tarif-Wechsel („Blaze“) oder eine Kreditkarte vorschlägt: **nicht** annehmen. Spark reicht.
6. Links im Menü: **Ausführen → Test Lab** (ggf. unter „Alle Produkte“) einmal öffnen und auf **Los geht's** klicken, falls angeboten.

## 2. Zugangsschlüssel für GitHub erstellen
1. Öffne <https://console.cloud.google.com/iam-admin/serviceaccounts> und wähle oben das Projekt **Protokoll KI**.
2. **+ Dienstkonto erstellen**.
3. Name: `github-tests` → **Erstellen und fortfahren**.
4. Bei „Rolle auswählen“: **Einfach → Bearbeiter** (englisch: Basic → Editor) → **Weiter** → **Fertig**.
5. In der Liste auf das neue Konto `github-tests@…` klicken → Reiter **Schlüssel**.
6. **Schlüssel hinzufügen → Neuen Schlüssel erstellen → JSON → Erstellen**. Eine `.json`-Datei wird heruntergeladen.

> Diese Datei ist wie ein Passwort: nicht weitergeben, nur bei GitHub einfügen.

## 3. Schlüssel bei GitHub hinterlegen
1. Öffne <https://github.com/Dcabrerizo-lab/Protokoll-KI/settings/secrets/actions/new>.
2. **Name:** `FIREBASE_SERVICE_ACCOUNT`
3. **Secret:** die heruntergeladene `.json`-Datei mit einem Texteditor öffnen, **alles** markieren, kopieren und hier einfügen.
4. **Add secret** klicken. Danach darfst du die `.json`-Datei löschen.

## 4. Test starten
- Automatisch: bei jeder neuen Demo-Version (Branch `demo`).
- Von Hand: <https://github.com/Dcabrerizo-lab/Protokoll-KI/actions/workflows/real-devices.yml> → **Run workflow** → Auswahl `standard` (3 Handys) → **Run workflow**.

Ein Durchlauf dauert ca. 30–50 Minuten. Das Ergebnis steht auf der Seite des Laufs unter **Summary**
(✅/❌ je Handy, Dauer, Speicherverbrauch, Link zum Video in Firebase).

## Was getestet wird
Je Handy (Test: `app/src/androidTest/java/de/protokollki/RealDeviceTest.kt`):
1. Modellpaket herunterladen wie in der App (Galaxy S23 und Pixel: **Premium**, Galaxy A: die Empfehlung der App).
2. Besprechung (69 s, 3 Personen) bis zum fertigen Protokoll, PDF- und Word-Export.
3. Lange Besprechung (4,7 min) bis zum fertigen Protokoll – prüft den Speicher beim Protokollschreiben.

Gemessen werden Dauer, maximaler Speicher der App und freier RAM. Abstürze (auch im Sprachmodell) werden aus dem Systemprotokoll gemeldet.

## Auswahl der Handys
- `standard`: Galaxy S23-Familie + Pixel 8/9 + Galaxy A (3 von 5 Tagestests)
- `s23`: nur ein Galaxy S23 (1 Test)
- `alle`: 5 Handys (verbraucht das ganze Tageskontingent)
- Eigene Liste im Feld „geraete“, z. B. `dm1q:34:premium` (Modell-ID aus dem Test-Lab-Katalog).
