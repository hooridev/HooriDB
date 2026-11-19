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
DefaultDirName={localappdata}\Programs\HooriDB
DisableDirPage=yes
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
SetupIconFile={#Bundle}\HooriDB.ico
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
