# OPTIONAL: Run once as the Windows account that owns the encrypted pairing key.
# Creates a visible/minimized startup shortcut so the encoder restarts on sign-in.
$ErrorActionPreference = "Stop"
$startup = [Environment]::GetFolderPath("Startup")
$source = Join-Path $PSScriptRoot "start-processor.ps1"
if (!(Test-Path $source)) { throw "start-processor.ps1 not found" }
$link = Join-Path $startup "AddisReel Video Optimizer.lnk"
$wsh = New-Object -ComObject WScript.Shell
$shortcut = $wsh.CreateShortcut($link)
$shortcut.TargetPath = "$PSHOME\powershell.exe"
$shortcut.Arguments = '-NoProfile -ExecutionPolicy Bypass -File "' + $source + '"'
$shortcut.WorkingDirectory = $PSScriptRoot
$shortcut.WindowStyle = 7
$shortcut.Description = "Process AddisReel movie uploads when I sign in"
$shortcut.Save()
Write-Host "AddisReel optimizer will start when this Windows user signs in."
Write-Host "Remove this shortcut to disable it: $link"
