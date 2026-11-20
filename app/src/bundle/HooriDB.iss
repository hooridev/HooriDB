; The Windows installer of HooriDB, made from the app image that mvn -Pbundle verify builds:
;   ISCC /DVersion=X.Y.Z app\src\bundle\HooriDB.iss
; It installs for the user alone, into a folder the user may write, so that the updater's helper
; replaces the app without asking for rights.

#ifndef Version
  #error Pass the version: ISCC /DVersion=X.Y.Z HooriDB.iss
#endif
; Windows takes numbers only, as 1.2.3 of 1.2.3-beta.1 or 1.2.3+build.7.
#define Core Copy(Version, 1, Pos("-", Version + "-") - 1)
#define NumericVersion Copy(Core, 1, Pos("+", Core + "+") - 1)
#define Bundle "..\..\target\bundle"

[Setup]
AppId=dev.hoori.db
AppName=HooriDB
AppVersion={#Version}
VersionInfoVersion={#NumericVersion}
AppPublisher=HooriDB contributors
AppCopyright=Copyright (C) Patrick Trautmann and the HooriDB contributors
AppPublisherURL=https://db.hoori.dev
AppSupportURL=https://db.hoori.dev
AppUpdatesURL=https://db.hoori.dev
; Installed apps shows the version beside the name already.
UninstallDisplayName=HooriDB
DefaultDirName={localappdata}\Programs\HooriDB
DisableDirPage=yes
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
SetupIconFile={#Bundle}\HooriDB.ico
LicenseFile=..\..\..\LICENSE
UninstallDisplayIcon={app}\HooriDB.exe
OutputDir={#Bundle}
OutputBaseFilename=HooriDB-{#Version}-windows-x64-setup
WizardStyle=modern

[Files]
Source: "{#Bundle}\image\HooriDB\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
; The ID that Window.identity gives the app's windows, so that a pinned taskbar button is theirs.
Name: "{autoprograms}\HooriDB"; Filename: "{app}\HooriDB.exe"; AppUserModelID: "dev.hoori.db"

[UninstallDelete]
; Also the files that later versions brought, which the installer does not know of.
Type: filesandordirs; Name: "{app}"

[Code]
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Folder: String;
begin
  // Another folder, by /DIR, /LOADINF or an earlier installation, could be one the user may not
  // write, or one the uninstaller would delete whole.
  Folder := ExpandConstant('{localappdata}\Programs\HooriDB');
  if CompareText(RemoveBackslashUnlessRoot(ExpandConstant('{app}')), Folder) <> 0 then
    Result := 'HooriDB installs only into ' + Folder + '.';
end;

// Whether HooriDB runs from the installed folder. Setup notices that itself (CloseApplications)
// and stops before it changes anything; the uninstaller would delete what it can and leave the
// rest behind a running app.
function Running(): Boolean;
var
  Locator, Service, Processes, Process: Variant;
  I: Integer;
begin
  Result := False;
  try
    Locator := CreateOleObject('WbemScripting.SWbemLocator');
    Service := Locator.ConnectServer('.', 'root\CIMV2');
    Processes := Service.ExecQuery(
      'SELECT ExecutablePath FROM Win32_Process WHERE Name = ''HooriDB.exe''');
    for I := 0 to Processes.Count - 1 do
    begin
      Process := Processes.ItemIndex(I);
      if not VarIsNull(Process.ExecutablePath) then
        if CompareText(Process.ExecutablePath, ExpandConstant('{app}\HooriDB.exe')) = 0 then
        begin
          Result := True;
          Exit;
        end;
    end;
  except
    Log('Could not tell whether HooriDB runs: ' + GetExceptionMessage);
  end;
end;

// After the user confirmed, right before files go: HooriDB may have been started meanwhile.
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usAppMutexCheck then
    while Running() do
    begin
      if UninstallSilent() then
      begin
        Log('HooriDB is running; the silent uninstall stops.');
        Abort;
      end;
      if MsgBox('HooriDB is running. Close it, then click OK to continue, or Cancel to keep it'
        + ' installed.', mbError, MB_OKCANCEL) <> IDOK then
        Abort;
    end;
end;
