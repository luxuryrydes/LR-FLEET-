@echo off
rem Connects this PC to your Cloudflare tunnel as the Windows service "cloudflared".
rem Paste the install command (or just the token) that Cloudflare shows on the tunnel's "Install and run a connector" page.
setlocal
set "ROOT=%~dp0"

net session >nul 2>&1
if errorlevel 1 (
  echo Asking Windows for administrator rights...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b 0
)

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$exe = 'C:\Cloudflared\bin\cloudflared.exe';" ^
  "if (-not (Test-Path $exe)) { $exe = Join-Path '%ROOT%' '.tools\cloudflared.exe' };" ^
  "if (-not (Test-Path $exe)) { Write-Host 'cloudflared.exe not found. Run share-online.cmd once to download it.' -ForegroundColor Red; exit 1 };" ^
  "$saved = Join-Path '%ROOT%' '.tools\cloudflare-tunnel-token.txt';" ^
  "Write-Host 'Cloudflare dashboard -> Zero Trust -> Networks -> Tunnels -> your tunnel -> Configure -> Windows.';" ^
  "Write-Host 'Copy the command shown there and paste it here (right-click pastes), then press Enter.';" ^
  "if (Test-Path $saved) { Write-Host 'Or just press Enter to use the token saved last time.' };" ^
  "$line = Read-Host 'Paste';" ^
  "if (-not $line -and (Test-Path $saved)) { $line = Get-Content $saved -Raw };" ^
  "$tok = ($line -split '\s+' | Where-Object { $_ -like 'eyJ*' } | Select-Object -First 1);" ^
  "if (-not $tok) { Write-Host 'No token found in what you pasted (it starts with eyJ).' -ForegroundColor Red; exit 1 };" ^
  "try { $info = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($tok.PadRight([Math]::Ceiling($tok.Length / 4) * 4, '='))) | ConvertFrom-Json; Write-Host ('Tunnel id: ' + $info.t) } catch {};" ^
  "Set-Content -Path $saved -Value $tok -NoNewline;" ^
  "$log = Join-Path $env:TEMP 'cloudflared-install.log';" ^
  "if (Get-Service cloudflared -ErrorAction SilentlyContinue) { Write-Host 'Removing the old connector service...'; Start-Process $exe -ArgumentList 'service','uninstall' -Wait -NoNewWindow -RedirectStandardError $log; Start-Sleep 3 };" ^
  "Write-Host 'Installing the connector service...';" ^
  "Start-Process $exe -ArgumentList 'service','install',$tok -Wait -NoNewWindow -RedirectStandardError $log;" ^
  "for ($i = 0; $i -lt 15; $i++) { $svc = Get-Service cloudflared -ErrorAction SilentlyContinue; if ($svc -and $svc.Status -ne 'Running') { Start-Service cloudflared -ErrorAction SilentlyContinue }; if ($svc -and $svc.Status -eq 'Running') { break }; Start-Sleep 2 };" ^
  "Start-Sleep 8;" ^
  "$svc = Get-Service cloudflared -ErrorAction SilentlyContinue;" ^
  "if (-not $svc -or $svc.Status -ne 'Running') { Write-Host ('The service did not start. Details: ' + $log) -ForegroundColor Red; exit 1 };" ^
  "$pid2 = (Get-CimInstance Win32_Service -Filter \"Name='cloudflared'\").ProcessId;" ^
  "$up = Get-NetTCPConnection -OwningProcess $pid2 -State Established -ErrorAction SilentlyContinue | Where-Object { $_.RemotePort -eq 7844 -or $_.RemotePort -eq 443 };" ^
  "if ($up) { Write-Host 'Connected. In Cloudflare the tunnel now shows HEALTHY.' -ForegroundColor Green } else { Write-Host 'Service running but not connected yet. If the tunnel stays DOWN in Cloudflare, the token is from a deleted tunnel: copy a fresh one.' -ForegroundColor Yellow };" ^
  "Write-Host 'Make sure the tunnel has these Public hostnames:  fleet.lrbilling.in -> HTTP localhost:4173   and   billing.lrbilling.in -> HTTP localhost:4000'"
echo.
pause
