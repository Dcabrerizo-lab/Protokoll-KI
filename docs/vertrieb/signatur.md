# Signatur einrichten

## Android: geheimer Verkaufs-Schlüssel

Warum: Der bisherige Schlüssel `keystore/protokollki.jks` steht samt Passwort im öffentlichen Repo. Er bleibt für Test- und Demo-Versionen (Releases „latest“ und „demo“), damit sich diese weiter über die alte Version installieren lassen. Für den Verkauf braucht es einen eigenen, geheimen Schlüssel.

### 1. Schlüssel erzeugen (einmalig, am PC mit Java oder Android Studio)
```
keytool -genkeypair -v -keystore protokollki-store.jks -alias protokollki-store \
  -keyalg RSA -keysize 4096 -validity 10000
```
Passwort gut merken. Die Datei **sicher sichern** (z. B. Passwortmanager + USB-Stick). Ohne sie lassen sich direkt verkaufte APKs nie wieder aktualisieren.

### 2. In GitHub hinterlegen
Repo → Settings → Secrets and variables → Actions → „New repository secret“:
| Name | Inhalt |
|---|---|
| `PK_KEYSTORE_BASE64` | Datei als Base64: Windows PowerShell `[Convert]::ToBase64String([IO.File]::ReadAllBytes("protokollki-store.jks"))`, Linux `base64 -w0 protokollki-store.jks` |
| `PK_KEYSTORE_PASSWORD` | Passwort |
| `PK_KEY_ALIAS` | `protokollki-store` |
| `PK_KEY_PASSWORD` | Passwort des Schlüssels (meist dasselbe) |

### 3. Bauen
Actions → „Verkaufsversion bauen (Play Store)“ → „Run workflow“. Oder ein Tag `v1.0.0` setzen: dann entsteht zusätzlich ein Entwurfs-Release mit `ProtokollKI.aab` und `ProtokollKI-store.apk`. Der Ablauf prüft, dass nicht der öffentliche Test-Schlüssel benutzt wurde, und bricht ab, solange Impressum oder Datenschutz Platzhalter enthalten.

### Google Play
In der Play Console „Play App Signing“ aktiv lassen (Standard). Dann ist der Schlüssel oben nur der Upload-Schlüssel; Google signiert die ausgelieferte App. Geht der Upload-Schlüssel verloren, kann Google ihn zurücksetzen.

Hinweis: Play-Store-Version und direkt verkaufte APK lassen sich nicht übereinander installieren (andere Signatur). Kunden bleiben bei einem Weg.

## Windows: Codesignatur

Ohne Signatur zeigt Windows beim Installer „Der Computer wurde durch Windows geschützt – Unbekannter Herausgeber“. Seit 2023 gibt es Codesignatur-Zertifikate nur noch auf Hardware-Token oder in der Cloud; für automatisches Signieren in GitHub eignet sich am besten:

**Azure Trusted Signing** (Microsoft, ca. 10 US-Dollar/Monat). Voraussetzung: Firma bzw. Gewerbe mit überprüfbarer Identität (Einzelpersonen derzeit nur in bestimmten Ländern). Nach der Einrichtung im Azure-Portal diese GitHub-Secrets setzen, dann signiert `pc.yml` Programm und Installer automatisch:

| Secret | Inhalt |
|---|---|
| `AZURE_TENANT_ID`, `AZURE_CLIENT_ID`, `AZURE_CLIENT_SECRET` | App-Registrierung mit Rolle „Trusted Signing Certificate Profile Signer“ |
| `AZURE_SIGNING_ENDPOINT` | z. B. `https://weu.codesigning.azure.net/` |
| `AZURE_SIGNING_ACCOUNT` | Name des Trusted-Signing-Kontos |
| `AZURE_CERT_PROFILE` | Name des Zertifikatsprofils |

Alternativen: OV-Zertifikat mit Cloud-Signatur (z. B. SSL.com eSigner, ca. 200–300 €/Jahr) oder Verkauf über den Microsoft Store (signiert Microsoft selbst).

Linux (.deb, AppImage) braucht keine Signatur.
