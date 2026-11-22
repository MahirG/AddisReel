# Start AddisReel's local encoder. Pairing key is DPAPI-encrypted for this Windows user.
$ErrorActionPreference = 'Stop'
$keyFile = Join-Path $env:LOCALAPPDATA 'AddisReelProcessor\token.dpapi'
if (-not (Test-Path $keyFile)) {
  Write-Host 'Get a one-time processor key in AddisReel Studio > My Account.'
  $key = Read-Host 'Paste processor pairing key (hidden)' -AsSecureString
  New-Item -Path (Split-Path $keyFile) -ItemType Directory -Force | Out-Null
  ConvertFrom-SecureString $key | Set-Content -Path $keyFile -Encoding UTF8
  Write-Host 'Pairing key encrypted for this Windows user.'
}
$secure = ConvertTo-SecureString (Get-Content $keyFile -Raw)
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
  $env:ADDISREEL_PROCESSOR_TOKEN = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
  if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) { throw 'FFmpeg not found in PATH' }
  if (-not (Get-Command ffprobe -ErrorAction SilentlyContinue)) { throw 'FFprobe not found in PATH' }
  $script = Join-Path $PSScriptRoot 'addisreel_processor.py'
  if (Get-Command py -ErrorAction SilentlyContinue) { & py -3 $script }
  else { & python $script }
} finally {
  $env:ADDISREEL_PROCESSOR_TOKEN = ''
  [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
}
