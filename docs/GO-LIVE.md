# Going live from this PC with Cloudflare Tunnel

The app runs on this PC and colleagues open it at **https://fleet.lrbilling.in**. Cloudflare carries the traffic; nothing on
your router or firewall is opened.

```
Colleague's browser ──https──▶ Cloudflare ──tunnel──▶ this PC: website (port 4173) ──▶ API + database (ports 8080, 54329)
```

## Every day

Double-click **`run-local.cmd`** and keep its window open (it can be minimised). It starts everything, checks the tunnel and
confirms the public address works. To stop, double-click **`stop-local.cmd`**, press Ctrl+C in the window, or close it.
The database is closed safely each way. Never end Java from Task Manager.

| Need | Do |
|---|---|
| Start, or start again after an update | `run-local.cmd` (it rebuilds what changed) |
| Stop | `stop-local.cmd` |
| Back up right now | `backup-now.cmd` (the app must be running) |
| Restore a backup | `restore-backup.cmd`. **All current data is replaced**: drag the backup zip in, type YES, then restart the app |
| Something is wrong | Read the summary in the `run-local` window, or `data\logs\` (`run-local.log`, `api.log`, `worker.log`, `web.log`) |

Running `run-local.cmd` again while the app runs starts nothing twice: it reuses what is running and only checks.
`start-live.cmd` and `stop-live.cmd` still work and simply call the two files above.

## One-time setup (already done on the main PC)

1. **Install the app.** Double-click `run-local.cmd`. The first run downloads Java and Maven into `.tools\` and installs the
   packages.
2. **Change the admin password.** Until every administrator has a strong password, the website opens on this PC only
   (http://localhost:4173) and the public address shows "Not open yet". Sign in, go to *Users & roles*, change it, then start
   `run-local.cmd` again. Create a user for each colleague (VIEWER, OPERATOR, MANAGER or ADMIN).
3. **Connect the tunnel.** Double-click **`connect-tunnel.cmd`** (it asks for administrator rights). If the Cloudflare service is
   already connected it changes nothing. Otherwise paste the command from Cloudflare (*Zero Trust → Networks → Tunnels → your
   tunnel → Configure → Windows*). It installs the Windows service `cloudflared`: it starts with Windows, reconnects after an
   internet drop, and Windows restarts it if it stops. The token stays in Windows' protected service file, never in this
   folder. One service serves every hostname on the tunnel, so Fleet and Billing Expo share it.
4. **Routes in Cloudflare** (*your tunnel → Routes*): `fleet.lrbilling.in` → `http://localhost:4173` (no `/` at the end) and
   `billing.lrbilling.in` → `http://localhost:4000`. Each hostname's DNS record (CNAME, Proxied) must point at that same tunnel:
   `<tunnel-id>.cfargotunnel.com`.
5. **(Optional) Start at sign-in and nightly backups:** double-click `install-autostart.cmd`. It asks for a backup folder
   (choose OneDrive or Google Drive so a copy leaves this PC) and keeps the last 14 backups. `uninstall-autostart.cmd` removes it.
6. **(Recommended)** Cloudflare *Access → Applications → Self-hosted* → `fleet.lrbilling.in` → allow only your colleagues' emails.

Keep the PC on and awake: *Settings → System → Power → Sleep: Never (when plugged in)*.

## If the public address does not work

`run-local.cmd` says which part fails. In short:

| You see | Meaning | Fix |
|---|---|---|
| Error **1033** / HTTP 530 | The DNS record points at a tunnel with no connector on this PC | Point the `fleet` CNAME at the tunnel the service runs, or run `connect-tunnel.cmd` with that tunnel's command |
| HTTP **502** | The tunnel reaches this PC but nothing answers | Start `run-local.cmd`; make sure the route is `http://localhost:4173` |
| "Not open yet" | An administrator still has a default password | Change it on this PC, then start `run-local.cmd` again |
| "Almost there" / "Invalid CORS request" | The route points at the developer website (5173) | Set the route to `http://localhost:4173` |

## Safety

* Every colleague signs in with their own user and role. Wrong passwords lock the account for 15 minutes after 5 tries.
* Logins are signed with a secret created on this PC (`data\jwt-secret.txt`). Keep the `data` folder private.
* Every change is in the *Audit log* with the user and their IP address.
* The database and API are reachable only on this PC. Only the website goes through Cloudflare.
* A backup contains all fleet data and uploaded scans. Store it somewhere private and outside the app folder.
