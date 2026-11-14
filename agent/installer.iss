; Inno Setup skripta za SweetSpot Agent.
; Izgradnja:  ISCC /DAppVersion=1.1.0 agent\installer.iss
; Očekuje dist\SweetSpotAgent\ (PyInstaller) i build\sweetspot.ico u korijenu repozitorija.

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif

[Setup]
AppId={{8B6C1E0A-5D0B-4A53-9C59-5E0B1C7A2F11}
AppName=SweetSpot Agent
AppVersion={#AppVersion}
AppPublisher=SweetSpot
AppPublisherURL=https://github.com/moordrak/SweetSpot
DefaultDirName={autopf}\SweetSpot Agent
DisableProgramGroupPage=yes
; Instalacija po korisniku, bez administratorskih prava
PrivilegesRequired=lowest
OutputDir=..\dist
OutputBaseFilename=SweetSpot-Agent-Setup-{#AppVersion}
SetupIconFile=..\build\sweetspot.ico
UninstallDisplayIcon={app}\SweetSpotAgent.exe
UninstallDisplayName=SweetSpot Agent
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
; Agent drži ovaj mutex dok radi, pa instalacija traži da se prvo zatvori
AppMutex=SweetSpotAgentMutex
ArchitecturesInstallIn64BitMode=x64compatible

[Tasks]
Name: "autostart"; Description: "Pokreni SweetSpot Agent pri prijavi u Windows"; GroupDescription: "Dodatne opcije:"
Name: "desktopicon"; Description: "Ikona na radnoj površini"; GroupDescription: "Dodatne opcije:"; Flags: unchecked

[Files]
Source: "..\dist\SweetSpotAgent\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion

[Icons]
Name: "{autoprograms}\SweetSpot Agent"; Filename: "{app}\SweetSpotAgent.exe"
Name: "{autodesktop}\SweetSpot Agent"; Filename: "{app}\SweetSpotAgent.exe"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "SweetSpot Agent"; ValueData: """{app}\SweetSpotAgent.exe"""; Tasks: autostart

[Run]
Filename: "{app}\SweetSpotAgent.exe"; Description: "Pokreni SweetSpot Agent"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/IM SweetSpotAgent.exe /F"; Flags: runhidden; RunOnceId: "StopAgent"

[Code]
// Pri deinstalaciji ukloni pokretanje s Windowsima (moglo je biti uključeno i iz izbornika ikone).
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usPostUninstall then
    RegDeleteValue(HKEY_CURRENT_USER, 'Software\Microsoft\Windows\CurrentVersion\Run', 'SweetSpot Agent');
end;
