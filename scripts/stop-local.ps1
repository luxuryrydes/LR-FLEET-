# LR Fleet Manager - safe stop. Started by stop-local.cmd.
# Sends the app the same Ctrl+C that closing its window would, so the API shuts its database down cleanly, then waits.
# It never force-kills the API or the database. The Cloudflare Tunnel service keeps running (it is shared with Billing Expo).
$root = Split-Path $PSScriptRoot -Parent
$data = Join-Path $root 'data'
$logDir = Join-Path $data 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$runLog = Join-Path $logDir 'run-local.log'
function Say([string]$m = '') { Write-Host $m; try { Add-Content -Path $runLog -Value ("{0:yyyy-MM-dd HH:mm:ss}  {1}" -f (Get-Date), $m) } catch { } }
function Owner([int]$port) {
  $l = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($l) { Get-CimInstance Win32_Process -Filter "ProcessId=$($l.OwningProcess)" }
}

# Only this app's own processes: the API jar, the website (vite preview) and the import worker.
$targets = @()
$api = Owner 8080; if ($api -and $api.CommandLine -match 'fleet-backend') { $targets += $api }
$web = Owner 4173; if ($web -and $web.Name -eq 'node.exe' -and $web.CommandLine -match 'vite') { $targets += $web }
$targets += @(Get-CimInstance Win32_Process -Filter "Name='python.exe'" | Where-Object { $_.CommandLine -match 'fleet_worker\.main' })
if (-not $targets) { Say 'LR Fleet Manager is not running.' }

function Send-CtrlC([int]$procId) {
  # A separate hidden PowerShell attaches to that process's console and sends Ctrl+C there (this window keeps its own).
  $code = @"
Add-Type -Name K -Namespace W -MemberDefinition '
[DllImport("kernel32.dll")] public static extern bool FreeConsole();
[DllImport("kernel32.dll")] public static extern bool AttachConsole(uint p);
[DllImport("kernel32.dll")] public static extern bool SetConsoleCtrlHandler(System.IntPtr h, bool add);
[DllImport("kernel32.dll")] public static extern bool GenerateConsoleCtrlEvent(uint e, uint g);'
[W.K]::FreeConsole() | Out-Null
if (-not [W.K]::AttachConsole($procId)) { exit 3 }
[W.K]::SetConsoleCtrlHandler([System.IntPtr]::Zero, `$true) | Out-Null
[W.K]::GenerateConsoleCtrlEvent(0, 0) | Out-Null
Start-Sleep -Milliseconds 500
exit 0
"@
  $h = Start-Process powershell -ArgumentList '-NoProfile', '-EncodedCommand', ([Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($code))) -WindowStyle Hidden -Wait -PassThru
  return $h.ExitCode
}

if ($targets) {
  Say 'Stopping LR Fleet Manager (the database is closed cleanly, this can take up to a minute)...'
  # Parts started by run-local.cmd share one window, so the first Ctrl+C usually reaches all of them.
  $sent = $false
  foreach ($t in $targets) {
    if ($sent) { Start-Sleep -Seconds 3 }
    if (-not (Get-Process -Id $t.ProcessId -ErrorAction SilentlyContinue)) { continue }
    if ((Send-CtrlC $t.ProcessId) -eq 3) {
      Start-Sleep -Seconds 3
      if (Get-Process -Id $t.ProcessId -ErrorAction SilentlyContinue) { Say "  $($t.Name) (process $($t.ProcessId)) has no window to send the stop signal to; close it yourself. It was not force-stopped." }
    } else { $sent = $true }
  }
  $deadline = (Get-Date).AddSeconds(60)
  while ((Get-Date) -lt $deadline -and ($targets | Where-Object { Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue })) { Start-Sleep -Milliseconds 500 }
  $left = @($targets | Where-Object { Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue })
  if ($left) {
    Say ('Still closing after 60 seconds: ' + (($left | ForEach-Object { "$($_.Name) ($($_.ProcessId))" }) -join ', ') + '. Nothing was forced; give it a minute and run this again.')
    exit 1
  }
}

# A database left behind by an API that was killed earlier: stop it cleanly (pg_ctl, never a kill).
if ((Owner 54329) -and -not (Owner 8080) -and (Test-Path (Join-Path $data 'pg\postmaster.pid'))) {
  foreach ($d in Get-ChildItem (Join-Path $env:TEMP 'embedded-pg') -Directory -Filter 'PG-*' -ErrorAction SilentlyContinue) {
    $ctl = Join-Path $d.FullName 'bin\pg_ctl.exe'
    if (Test-Path $ctl) { & $ctl -D (Join-Path $data 'pg') stop -m fast *> $null }
  }
  if (Owner 54329) { Say 'A database is still running on port 54329; restart Windows if run-local.cmd cannot start.' } else { Say 'Stopped a database that was left running.' }
}

# Everything is closed, so the start window has nothing left to protect: close it if it is waiting on a prompt.
Get-CimInstance Win32_Process -Filter "Name='cmd.exe' or Name='powershell.exe'" | Where-Object { $_.CommandLine -match 'lr-fleet-manager.*run-local\.(cmd|ps1)|scripts\\run-local\.ps1' -and $_.CommandLine -notmatch 'stop-local' -and $_.ProcessId -ne $PID } |
  Where-Object { $_.CommandLine -match [regex]::Escape($root) } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
if ($targets) { Say 'LR Fleet Manager stopped. The database was closed safely.' }
