# Going live from this PC with Cloudflare Tunnel

The app runs on this PC and your colleagues open it at your own address, for example `https://fleet.yourcompany.com`.
Cloudflare carries the traffic; nothing on your router or firewall is opened. Vercel and Render are not needed.

```
Colleague's browser ──https──▶ Cloudflare ──tunnel──▶ this PC: website (port 4173) ──▶ API + database (port 8080)
```

## One-time setup

**1. Install the app once.** Double-click `run-local.cmd` and wait until the website opens. That installs Java, Maven, Python and
website packages, and creates the database. Then close its windows.

**2. Change the admin password.** Start `run-local.cmd` again, sign in, go to *Users & roles*, edit the admin user and set a strong
password. Create a user for each colleague (VIEWER, OPERATOR, MANAGER or ADMIN). Close the windows.
The live start refuses to publish while an admin still has a default password.

**3. Create the tunnel in Cloudflare.** You need a free Cloudflare account with your domain added to it.
1. Cloudflare dashboard → **Zero Trust** → **Networks** → **Tunnels** → **Create a tunnel** → *Cloudflared* → name it `lr-fleet`.
2. On *Install and run a connector*, choose Windows and run the command Cloudflare shows in an **administrator** command
   prompt (`cloudflared.exe service install <token>`). That installs the connector as a **Windows service**: it starts with
   Windows and keeps all your public hostnames online, for example `fleet.lrbilling.in` and `billing.lrbilling.in`, from
   one connection. `start-live.cmd` uses this service and never starts a second tunnel.
3. **Public hostname** → subdomain `fleet`, your domain, type **HTTP**, URL **`localhost:4173`** → Save.
4. (Recommended) **Access** → **Applications** → *Self-hosted* → `fleet.yourcompany.com` → allow only your colleagues' email
   addresses. Cloudflare then emails them a code before they even see the sign-in page.

**4. Start it.** Double-click **`start-live.cmd`**. It builds the latest version and starts three minimised
*LR Fleet LIVE* windows: API + database, import worker and website (port 4173). It checks that the Cloudflare service is
running. The website is not started while an admin still has a default password.

**5. Make it automatic.** Double-click **`install-autostart.cmd`**:
* LR Fleet starts by itself whenever you sign in to Windows.
* The data is backed up every night at 23:30. It asks for a folder; choose a OneDrive or Google Drive folder so a copy leaves
  this PC. The last 14 backups are kept.

Keep the PC on and awake: *Settings → System → Power → Sleep: Never (when plugged in)*. After a restart (e.g. Windows Update),
sign in to Windows and the app starts again.

## If the address shows Cloudflare "Error 1033"

The tunnel has no connector online. Usually the token the Windows service uses belongs to a tunnel that was deleted
(`cloudflared` logs "Tunnel not found"). In Cloudflare open the tunnel you keep → *Configure* → *Windows*, copy the command, then
double-click **`connect-tunnel.cmd`** and paste it. It replaces the service's token (administrator rights asked) and checks the
connection. Make sure that tunnel has every public hostname you use (`fleet` → `localhost:4173`, `billing` → `localhost:4000`).

## If sign-in shows "Invalid CORS request" or an "Almost there" page

Your Cloudflare hostname points to the **developer** website (`localhost:5173`, from `run-local.cmd`). In the Cloudflare dashboard
(Zero Trust → Networks → Tunnels → your tunnel → *Public hostname*), change the service to **HTTP `localhost:4173`**. Then close the
`run-local.cmd` windows and start **`start-live.cmd`**. The developer website refuses all Cloudflare traffic on purpose: it is not
safe to put online.

## Everyday

| Need | Do |
|---|---|
| Stop the app | `stop-live.cmd` |
| Start it again / after an update | `stop-live.cmd`, then `start-live.cmd` (it rebuilds the new version) |
| Back up right now | `backup-now.cmd` (the app must be running) |
| Restore a backup | `restore-backup.cmd`. **All current data is replaced**: drag the backup zip in, type YES, then restart the app |
| Something is wrong | Look in `data\logs`: `api.log`, `worker.log`, `web.log`, `tunnel.log`, `live.log`, `backup.log` |
| Remove auto-start | `uninstall-autostart.cmd` (data and backups are kept) |

Each part restarts itself if it stops, and the Cloudflare connection reconnects by itself after an internet drop.
Don't run `run-local.cmd` while the live app is running: they use the same database and ports.

## Safety

* Every colleague signs in with their own user and role. Wrong passwords lock the account for 15 minutes after 5 tries.
* Logins are signed with a secret created on this PC (`data\jwt-secret.txt`). Keep the `data` folder private.
* Every change is in the *Audit log* with the user and their IP address.
* The database and API are reachable only on this PC. Only the website goes through Cloudflare.
* A backup contains all fleet data and uploaded scans. Store it somewhere private and **outside the app folder** (backups in
  the app folder are never added to GitHub or the update zip, but they don't belong there either).
