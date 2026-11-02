# LR Fleet Manager - one-click start. Started by run-local.cmd.
#  1. checks the project (installs Java/Maven/Python/website packages on the first run, rebuilds what changed)
#  2. starts (or reuses) the API with its database and the import worker   3. starts (or reuses) the live website (4173)
#  4. checks the Cloudflare Tunnel service (starts it if stopped)   5. confirms the public address shows this app.
# Everything runs in this one window, so Ctrl+C or closing the window shuts the database down cleanly.
# No tunnel token is read, stored or printed here: the tunnel is the Windows service set up once with connect-tunnel.cmd.
param([switch]$NoTunnel, [switch]$Auto, [int]$MaxRestarts = 3)
$ErrorActionPreference = 'Continue'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
$tools = Join-Path $root '.tools'
$data = Join-Path $root 'data'
$logDir = Join-Path $data 'logs'
New-Item -ItemType Directory -Force -Path $logDir, (Join-Path $data 'files') | Out-Null
$runLog = Join-Path $logDir 'run-local.log'
$apiPort = 8080; $dbPort = 54329; $webPort = 4173
$public = $(if ($env:PUBLIC_URL) { $env:PUBLIC_URL } else { 'https://fleet.lrbilling.in' }).TrimEnd('/')

function Say([string]$m = '') { Write-Host $m; try { Add-Content -Path $runLog -Value ("{0:yyyy-MM-dd HH:mm:ss}  {1}" -f (Get-Date), $m) } catch { } }
function Stop-Here([string]$m) {
  Say ''; Say "ERROR: $m"
  if ($script:procs -and @($script:procs.Values | Where-Object { -not $_.HasExited }).Count) { Say 'The parts that did start are still running in this window: close the window (or run stop-local.cmd) to stop them safely.' }
  Say ''; exit 1
}
function Tail([string]$file, [int]$n = 12) { if (Test-Path $file) { (Get-Content $file -Tail $n) -join "`n" } else { '' } }
function Probe([string]$url, [int]$sec = 6) {
  try { $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec $sec; return @{ ok = $true; code = [int]$r.StatusCode; body = [string]$r.Content } }
  catch { $code = 0; try { $code = [int]$_.Exception.Response.StatusCode } catch { }; return @{ ok = $false; code = $code; body = '' } }
}
function Owner([int]$port) {
  $l = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not $l) { return $null }
  return Get-CimInstance Win32_Process -Filter "ProcessId=$($l.OwningProcess)"
}
function Newest([string[]]$paths) {
  $f = Get-ChildItem -Path $paths -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $_.FullName -notmatch '\\(node_modules|dist|target|test)\\' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if ($f) { $f.LastWriteTime } else { [datetime]::MinValue }
}
function Rotate([string]$f) { if ((Test-Path $f) -and (Get-Item $f).Length -gt 10MB) { Move-Item -Force $f "$f.old" } }
function Download([string]$url, [string]$zip, [string]$what) {
  Say "      Downloading $what (first run only)..."
  & curl.exe -L --fail -s -o $zip $url; if ($LASTEXITCODE -ne 0) { Stop-Here "Could not download $what. Check the internet connection and run this again." }
  & tar.exe -xf $zip -C $tools; if ($LASTEXITCODE -ne 0) { Stop-Here "Could not unpack $what." }
  Remove-Item $zip
}

Say '=========================================='
Say ' LR FLEET MANAGER - STARTUP'
Say '=========================================='

# ---- [1/5] project configuration ------------------------------------------------------------------------------------
Say '[1/5] Checking project configuration...'
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { Stop-Here 'Node.js 20 or newer is not installed. Install it from https://nodejs.org and run this again.' }
$python = Get-Command python -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $tools | Out-Null
$jdk = Get-ChildItem $tools -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $jdk) {
  Download 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse' (Join-Path $tools 'jdk21.zip') 'Java 21'
  $jdk = Get-ChildItem $tools -Directory -Filter 'jdk-21*' | Select-Object -First 1
}
$mvn = Join-Path $tools 'apache-maven-3.9.9\bin\mvn.cmd'
if (-not (Test-Path $mvn)) { Download 'https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip' (Join-Path $tools 'maven.zip') 'Maven' }
$env:JAVA_HOME = $jdk.FullName
$env:PATH = "$($jdk.FullName)\bin;$(Split-Path $mvn);$env:PATH"
$java = Join-Path $jdk.FullName 'bin\java.exe'
$venvPy = Join-Path $root 'worker\.venv\Scripts\python.exe'
if (-not (Test-Path $venvPy)) {
  if (-not $python) { Stop-Here 'Python 3.11 or newer is not installed. Install it from https://www.python.org (tick "Add to PATH") and run this again.' }
  Say '      Setting up the import worker (first run only)...'
  & $python.Source -m venv (Join-Path $root 'worker\.venv') *>> (Join-Path $logDir 'build.log')
  & $venvPy -m pip install -q -r (Join-Path $root 'worker\requirements.txt') *>> (Join-Path $logDir 'build.log')
  if ($LASTEXITCODE -ne 0) { Stop-Here 'The import worker could not be installed. See data\logs\build.log.' }
}
if (-not (Test-Path (Join-Path $root 'frontend\node_modules'))) {
  Say '      Installing website packages (first run only)...'
  & npm.cmd --prefix (Join-Path $root 'frontend') install --no-audit --no-fund *>> (Join-Path $logDir 'build.log')
  if ($LASTEXITCODE -ne 0) { Stop-Here 'Website packages could not be installed. See data\logs\build.log.' }
}
# Logins are signed with a secret that exists only on this PC (never the development default from GitHub).
$secretFile = Join-Path $data 'jwt-secret.txt'
if (-not (Test-Path $secretFile)) {
  $bytes = New-Object byte[] 48; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
  Set-Content -Path $secretFile -Value ([Convert]::ToBase64String($bytes)) -NoNewline
  Say '      Created a private login secret for this PC (data\jwt-secret.txt). Everyone signs in again once.'
}
$env:JWT_SECRET = (Get-Content $secretFile -Raw).Trim()

$apiOwner = Owner $apiPort
$apiRunning = $apiOwner -and (Probe "http://127.0.0.1:$apiPort/actuator/health" 4).ok
$jar = Join-Path $root 'backend\target\fleet-backend-1.0.0.jar'
$webDist = Join-Path $root 'frontend\dist\index.html'
$apiStale = (-not (Test-Path $jar)) -or ((Newest @((Join-Path $root 'backend\src'), (Join-Path $root 'backend\pom.xml'))) -gt (Get-Item $jar -ErrorAction SilentlyContinue).LastWriteTime)
$webStale = (-not (Test-Path $webDist)) -or ((Newest @((Join-Path $root 'frontend\src'), (Join-Path $root 'frontend\index.html'), (Join-Path $root 'frontend\vite.config.ts'))) -gt (Get-Item $webDist -ErrorAction SilentlyContinue).LastWriteTime)
if ($apiStale -and -not $apiRunning) {
  Say '      Building the API (files changed since the last build, about a minute)...'
  & $mvn -q -f (Join-Path $root 'backend\pom.xml') -Plocal -DskipTests package *> (Join-Path $logDir 'build-api.log')
  if ($LASTEXITCODE -ne 0) { Stop-Here 'The API build failed. See data\logs\build-api.log.' }
} elseif ($apiStale) { Say '      Note: the API is already running; the newest version is used after stop-local.cmd and run-local.cmd.' }
if ($webStale -and -not (Owner $webPort)) {
  Say '      Building the website...'
  $env:VITE_API_BASE_URL = ''
  & npm.cmd --prefix (Join-Path $root 'frontend') run build *> (Join-Path $logDir 'build-web.log')
  if ($LASTEXITCODE -ne 0) { Stop-Here 'The website build failed. See data\logs\build-web.log.' }
}

# ---- [2/5] backend: API + database, import worker -------------------------------------------------------------------
Say '[2/5] Starting backend...'
$procs = @{}
function Start-Part([string]$name) {
  switch ($name) {
    'api' {
      Rotate (Join-Path $logDir 'api.log')
      # A database left behind by an API that was killed would block the next start: stop it cleanly first.
      if ((Test-Path (Join-Path $data 'pg\postmaster.pid')) -and -not (Owner $apiPort)) {
        foreach ($d in Get-ChildItem (Join-Path $env:TEMP 'embedded-pg') -Directory -Filter 'PG-*' -ErrorAction SilentlyContinue) {
          $ctl = Join-Path $d.FullName 'bin\pg_ctl.exe'
          if (Test-Path $ctl) { & $ctl -D (Join-Path $data 'pg') stop -m fast *> $null }
        }
      }
      $p = Start-Process -FilePath $java -ArgumentList '-Xmx1g', '-Duser.timezone=Asia/Kolkata', '-jar', "`"$jar`"" -WorkingDirectory (Join-Path $root 'backend') -NoNewWindow -PassThru -RedirectStandardOutput (Join-Path $logDir 'api.log') -RedirectStandardError (Join-Path $logDir 'api-error.log')
    }
    'worker' {
      Rotate (Join-Path $logDir 'worker.log')
      $env:WORKER_DATABASE_URL = "postgresql://postgres:postgres@localhost:$dbPort/postgres"
      $env:STORAGE_TYPE = 'local'
      $env:STORAGE_LOCAL_DIR = Join-Path $data 'files'
      $p = Start-Process -FilePath $venvPy -ArgumentList '-m', 'fleet_worker.main' -WorkingDirectory (Join-Path $root 'worker') -NoNewWindow -PassThru -RedirectStandardOutput (Join-Path $logDir 'worker.log') -RedirectStandardError (Join-Path $logDir 'worker-error.log')
    }
    'web' {
      Rotate (Join-Path $logDir 'web.log')
      $env:LRFLEET_LOCAL_ONLY = $(if ($script:unsafe) { '1' } else { '0' })
      $vite = Join-Path $root 'frontend\node_modules\vite\bin\vite.js'
      $p = Start-Process -FilePath (Get-Command node).Source -ArgumentList "`"$vite`"", 'preview' -WorkingDirectory (Join-Path $root 'frontend') -NoNewWindow -PassThru -RedirectStandardOutput (Join-Path $logDir 'web.log') -RedirectStandardError (Join-Path $logDir 'web-error.log')
    }
  }
  $null = $p.Handle   # keeps the handle open so the exit code can be read after the process stops
  $script:procs[$name] = $p
  return $p
}
function Wait-Url([string]$url, [int]$seconds, [string]$part) {
  $until = (Get-Date).AddSeconds($seconds)
  while ((Get-Date) -lt $until) {
    if ($part -and $script:procs[$part] -and $script:procs[$part].HasExited) { return $false }
    if ((Probe $url 3).ok) { return $true }
    Start-Sleep -Seconds 1
  }
  return $false
}
$reusedApi = $false
if ($apiRunning) {
  $reusedApi = $true
  Say "      API already running on port $apiPort - reusing it (no second copy is started)."
} else {
  if ($apiOwner) { Stop-Here ("Port $apiPort is in use by '{0}' (process {1}), which is not the LR Fleet API. Close that program, then run this again. Nothing was stopped." -f $apiOwner.Name, $apiOwner.ProcessId) }
  $dbOwner = Owner $dbPort
  if ($dbOwner -and $dbOwner.Name -ne 'postgres.exe') { Stop-Here ("Port $dbPort (database) is in use by '{0}' (process {1}). Close that program, then run this again. Nothing was stopped." -f $dbOwner.Name, $dbOwner.ProcessId) }
  $null = Start-Part 'api'
  Say '      Starting the API and database (up to a few minutes)...'
  if (-not (Wait-Url "http://127.0.0.1:$apiPort/actuator/health" 360 'api')) { Stop-Here ("The API did not start. See data\logs\api.log.`n" + (Tail (Join-Path $logDir 'api-error.log') 6) + "`n" + (Tail (Join-Path $logDir 'api.log') 8)) }
  Say "      API started (process $($procs['api'].Id)), log: data\logs\api.log"
}
$check = Probe "http://127.0.0.1:$apiPort/api/system/share-check" 6
$script:unsafe = -not ($check.ok -and $check.body -match '"defaultAdminPassword"\s*:\s*false')
if ($script:unsafe) { Say '      WARNING: an administrator still has a default password. The website opens on this PC only, not online.' }
$workerRunning = Get-CimInstance Win32_Process -Filter "Name='python.exe'" | Where-Object { $_.CommandLine -match 'fleet_worker\.main' }
if ($workerRunning) { Say '      Import worker already running - reusing it.' }
else { $null = Start-Part 'worker'; Start-Sleep -Seconds 2; if ($procs['worker'].HasExited) { Say ('      WARNING: the import worker stopped at once: ' + (Tail (Join-Path $logDir 'worker-error.log') 3)) } else { Say '      Import worker started.' } }

# ---- [3/5] frontend ---------------------------------------------------------------------------------------------------
Say '[3/5] Starting frontend...'
$webOwner = Owner $webPort
if ($webOwner) {
  if ($webOwner.Name -ne 'node.exe' -or -not (Probe "http://localhost:$webPort/" 4).ok) { Stop-Here ("Port $webPort is in use by '{0}' (process {1}), which is not the LR Fleet website. Close that program, then run this again. Nothing was stopped." -f $webOwner.Name, $webOwner.ProcessId) }
  Say "      Website already running on port $webPort - reusing it."
} else {
  $null = Start-Part 'web'
  if (-not (Wait-Url "http://localhost:$webPort/" 60 'web')) { Stop-Here ("The website did not start. See data\logs\web.log.`n" + (Tail (Join-Path $logDir 'web-error.log') 6)) }
  Say "      Website started on port $webPort (process $($procs['web'].Id))."
}
$page = Probe "http://localhost:$webPort/" 6
if (-not ($page.ok -and $page.body -match '<div id="root"')) { Stop-Here "The website on port $webPort did not return the app (HTTP $($page.code))." }

# ---- [4/5] Cloudflare Tunnel ----------------------------------------------------------------------------------------
Say '[4/5] Checking Cloudflare Tunnel...'
function Get-TunnelService { Get-Service -Name 'cloudflared' -ErrorAction SilentlyContinue }
function Get-TunnelConnections {
  # cloudflared reports its live connections to Cloudflare on a local status page (ports 20241-20245).
  $svcPid = (Get-CimInstance Win32_Service -Filter "Name='cloudflared'" -ErrorAction SilentlyContinue).ProcessId
  if (-not $svcPid) { return -1 }
  foreach ($p in (Get-NetTCPConnection -State Listen -OwningProcess $svcPid -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -ge 20241 -and $_.LocalPort -le 20245 }).LocalPort) {
    try { return [int](Invoke-RestMethod -Uri "http://127.0.0.1:$p/ready" -TimeoutSec 3).readyConnections } catch {
      try { return [int](($_.ErrorDetails.Message | ConvertFrom-Json).readyConnections) } catch { }
    }
  }
  return -1
}
function Ensure-Tunnel {
  $svc = Get-TunnelService
  if (-not $svc) { return 'NOT CONFIGURED' }
  if ($svc.Status -ne 'Running') {
    try { Start-Service -Name 'cloudflared' -ErrorAction Stop } catch {
      Say '      The tunnel service is stopped; asking Windows for permission to start it (one prompt)...'
      try { Start-Process -FilePath 'sc.exe' -ArgumentList 'start cloudflared' -Verb RunAs -Wait -WindowStyle Hidden } catch { }
    }
    for ($i = 0; $i -lt 20; $i++) { Start-Sleep -Milliseconds 500; if ((Get-TunnelService).Status -eq 'Running') { break } }
  }
  if ((Get-TunnelService).Status -eq 'Running') { return 'RUNNING' } else { return 'STOPPED' }
}
$tunnel = 'SKIPPED'
if ($NoTunnel) { Say '      Skipped (-NoTunnel).' } else {
  $tunnel = Ensure-Tunnel
  switch ($tunnel) {
    'RUNNING' {
      Say '      Cloudflare Tunnel service is running (reused, nothing new created).'
      $conns = -1
      for ($i = 0; $i -lt 15; $i++) { $conns = Get-TunnelConnections; if ($conns -gt 0) { break }; Start-Sleep -Seconds 2 }
      if ($conns -gt 0) { Say "      Connected to Cloudflare ($conns connections)." }
      elseif ($conns -eq 0) { Say '      Warning: the service is running but has no connection to Cloudflare. Check the internet; if it stays like this the tunnel token is from a deleted tunnel: run connect-tunnel.cmd with a fresh one.' }
      $extra = @(Get-Process -Name cloudflared -ErrorAction SilentlyContinue).Count
      if ($extra -gt 1) { Say "      Warning: $extra cloudflared processes are running. Only the service is needed; close any extra (hand-started) ones." }
    }
    'NOT CONFIGURED' { Say '      The Cloudflare Tunnel service is not installed on this PC. One-time setup: double-click connect-tunnel.cmd.' }
    'STOPPED' { Say '      The tunnel service could not be started. Open Services (services.msc) and start "Cloudflare Tunnel", or run connect-tunnel.cmd.' }
  }
}

# ---- [5/5] public address -----------------------------------------------------------------------------------------------
Say '[5/5] Verifying connection...'
function Test-Public {
  $r = Probe "$public/" 10
  if ($r.ok -and $r.body -match '<title>LR Fleet Manager</title>') { return 'REACHABLE' }
  if ($r.code -eq 403 -and $script:unsafe) { return 'BLOCKED (default admin password)' }
  return "UNREACHABLE (HTTP $($r.code))"
}
$publicState = 'NOT CHECKED'
if ($tunnel -ne 'SKIPPED') {
  $until = (Get-Date).AddSeconds(45)
  do { $publicState = Test-Public; if ($publicState -ne 'REACHABLE' -and -not $publicState.StartsWith('UNREACHABLE')) { break }; if ($publicState -eq 'REACHABLE') { break }; Start-Sleep -Seconds 3 } while ((Get-Date) -lt $until)
}
$allGood = ($publicState -eq 'REACHABLE') -or ($tunnel -eq 'SKIPPED' -and -not $script:unsafe)

Say '=========================================='
if ($allGood) { Say ' LR FLEET MANAGER IS RUNNING SUCCESSFULLY' } elseif ($script:unsafe) { Say ' LR FLEET MANAGER IS RUNNING ON THIS PC ONLY - CHANGE THE ADMIN PASSWORD' } else { Say ' LR FLEET MANAGER IS RUNNING LOCALLY - PUBLIC ADDRESS NOT REACHABLE' }
Say '=========================================='
Say "Local URL:          http://localhost:$webPort"
Say "Public URL:         $public"
Say ("Cloudflare Tunnel:  {0}" -f $(if ($tunnel -eq 'RUNNING' -and $publicState -eq 'REACHABLE') { 'CONNECTED' } elseif ($tunnel -eq 'RUNNING') { 'SERVICE RUNNING, ROUTE NOT ANSWERING' } else { $tunnel }))
if ($tunnel -ne 'SKIPPED') { Say "Public address:     $publicState" }
Say "Backend:            RUNNING (API port $apiPort, database port $dbPort, import worker)"
Say "Frontend:           RUNNING (port $webPort)"
if ($script:unsafe) {
  Say ''
  Say "Open http://localhost:$webPort on this PC, go to Users & roles and give every administrator a strong password."
  Say 'Then close this window and start run-local.cmd again: the public address opens after that.'
} elseif (-not $allGood -and $tunnel -eq 'RUNNING') {
  Say ''
  Say 'The tunnel is connected to Cloudflare but the public address does not show this app. In Cloudflare check that:'
  Say "  - the tunnel has a route for the hostname above with service http://localhost:$webPort, and"
  Say '  - the DNS record for that hostname (CNAME, Proxied) points at THIS tunnel: <tunnel-id>.cfargotunnel.com.'
  Say 'HTTP 530 (Cloudflare error 1033): the DNS record points at a tunnel with no connector running.'
  Say "HTTP 502: the route points at the wrong address; it must be http://localhost:$webPort."
}
Say ''

if ($procs.Count -eq 0) { Say 'LR Fleet Manager was already running, so nothing else was started. This window can be closed.'; if (-not $Auto) { Start-Sleep -Seconds 8 }; exit 0 }

Say 'Keep this window open while LR Fleet Manager is in use (it can be minimised).'
Say 'To stop: double-click stop-local.cmd, press Ctrl+C here, or close this window (the database is closed safely).'
Say ''

# ---- supervise: restart a part that stops, keep the tunnel service running -------------------------------------------
$restarts = @{ api = 0; worker = 0; web = 0 }
$lastTunnelCheck = Get-Date
try {
  while ($true) {
    Start-Sleep -Seconds 5
    foreach ($name in @($procs.Keys)) {
      $p = $procs[$name]
      if (-not $p.HasExited) { continue }
      $err = Join-Path $logDir "$name-error.log"
      Say ("{0} stopped (exit code {1}). {2}" -f $name, $p.ExitCode, (Tail $err 4))
      if ($restarts[$name] -ge $MaxRestarts) { Say "$name stopped $($restarts[$name] + 1) times. Not restarting it again; see data\logs\$name.log. Close this window and run run-local.cmd after fixing it."; $procs.Remove($name); continue }
      $restarts[$name]++
      Say "Restarting $name ($($restarts[$name]) of $MaxRestarts)..."
      Start-Sleep -Seconds 5
      $null = Start-Part $name
      if ($name -eq 'api' -and -not (Wait-Url "http://127.0.0.1:$apiPort/actuator/health" 360 'api')) { Say 'The API did not come back. See data\logs\api.log.' }
      elseif ($name -eq 'web' -and -not (Wait-Url "http://localhost:$webPort/" 60 'web')) { Say 'The website did not come back. See data\logs\web.log.' }
      else { Say "$name is running again." }
    }
    if ($tunnel -ne 'SKIPPED' -and ((Get-Date) - $lastTunnelCheck).TotalSeconds -ge 60) {
      $lastTunnelCheck = Get-Date
      $svc = Get-TunnelService
      if ($svc -and $svc.Status -ne 'Running') { Say 'The Cloudflare Tunnel service stopped; starting it again...'; $null = Ensure-Tunnel }
      if ($publicState -ne 'REACHABLE' -and -not $script:unsafe) { $publicState = Test-Public; if ($publicState -eq 'REACHABLE') { Say "Public address is now reachable: $public" } }
    }
  }
} finally {
  foreach ($p in $procs.Values) { if (-not $p.HasExited) { $null = $p.WaitForExit(30000) } }
}
