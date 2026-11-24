; Inno-Setup-Skript: Installer ohne Administratorrechte für Protokoll KI (PC)
#define AppVersion GetEnv("PK_VERSION")
#if AppVersion == ""
  #define AppVersion "1.0.0"
#endif

[Setup]
AppId={{6E1A9C3B-4C2F-4A8E-9C51-7B2D9F0E5A11}
AppName=Protokoll KI
AppVersion={#AppVersion}
AppPublisher=Protokoll KI
DefaultDirName={localappdata}\Programs\Protokoll KI
DefaultGroupName=Protokoll KI
DisableProgramGroupPage=yes
DisableDirPage=yes
PrivilegesRequired=lowest
OutputDir=dist
OutputBaseFilename=ProtokollKI-PC-Setup
SetupIconFile=build\icon.ico
UninstallDisplayIcon={app}\ProtokollKI.exe
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ChangesAssociations=yes
CloseApplications=yes

[Languages]
Name: "de"; MessagesFile: "compiler:Languages\German.isl"

[Tasks]
Name: "desktopicon"; Description: "Symbol auf dem Desktop anlegen"; GroupDescription: "Zusätzlich:"

[Files]
Source: "dist\ProtokollKI\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion

[Icons]
Name: "{group}\Protokoll KI"; Filename: "{app}\ProtokollKI.exe"
Name: "{userdesktop}\Protokoll KI"; Filename: "{app}\ProtokollKI.exe"; Tasks: desktopicon

[Registry]
; .protokoll-Dateien (vom Handy) per Doppelklick öffnen
Root: HKCU; Subkey: "Software\Classes\.protokoll"; ValueType: string; ValueName: ""; ValueData: "ProtokollKI.Datei"; Flags: uninsdeletevalue
Root: HKCU; Subkey: "Software\Classes\ProtokollKI.Datei"; ValueType: string; ValueName: ""; ValueData: "Protokoll KI – Besprechung"; Flags: uninsdeletekey
Root: HKCU; Subkey: "Software\Classes\ProtokollKI.Datei\DefaultIcon"; ValueType: string; ValueName: ""; ValueData: "{app}\ProtokollKI.exe,0"
Root: HKCU; Subkey: "Software\Classes\ProtokollKI.Datei\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\ProtokollKI.exe"" ""%1"""

[Run]
Filename: "{app}\ProtokollKI.exe"; Description: "Protokoll KI jetzt starten"; Flags: nowait postinstall skipifsilent
