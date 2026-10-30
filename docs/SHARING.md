# Sharing the app with colleagues (Cloudflare Tunnel)

The app runs on your PC (`run-local.cmd`). `share-online.cmd` gives colleagues a `https://` link to it through a Cloudflare
Tunnel, so they can use it from anywhere, with no router or firewall changes. Your PC makes an outgoing connection to Cloudflare;
nothing on your PC is opened to the internet except the website itself.

## Every time

1. Start `run-local.cmd` and wait until the website opens.
2. Double-click `share-online.cmd`. The first time it downloads Cloudflare's `cloudflared` into `.tools\`.
3. It prints a link such as `https://something-random.trycloudflare.com`. Send that link to your colleagues.
4. Keep both windows open while colleagues work. Close the *share-online* window to stop sharing.

Colleagues sign in with **their own user**. Create one for each person under *Users & roles* and choose the role:
* **VIEWER**: can look at everything, cannot change anything.
* **OPERATOR**: can enter and import data.
* **MANAGER**: can also correct, void and delete.
* **ADMIN**: everything, including users and settings.

## Safety built in

* **Default passwords:** `share-online.cmd` refuses to start while any admin still has a published default password
  (`Admin@12345`). Change it first in the app.
* **Login security:** logins are signed with a random secret created on your PC (`data\jwt-secret.txt`), never one from GitHub.
* **What colleagues get:** the built website, not the developer server. The database is not reachable from outside.
* **Protection:** wrong passwords lock an account for 15 minutes after 5 tries, every request is rate-limited per colleague,
  and every change is in the audit log with the colleague's IP address.

## A permanent address (optional)

The free link changes every time and has no uptime guarantee. For a fixed address such as `fleet.yourcompany.com`, you need a free
Cloudflare account and your domain on Cloudflare:

1. Cloudflare dashboard → **Zero Trust → Networks → Tunnels → Create a tunnel** → *Cloudflared* → name it `lr-fleet`.
2. On the *Install connector* page, copy the long **token** (the text after `--token`). Save it as a single line in
   `.tools\cloudflare-tunnel-token.txt` in the app folder. Don't share it, and don't put it in GitHub (`.tools` is ignored).
3. **Public hostname**: subdomain `fleet`, your domain, service **HTTP** `localhost:4173`. Save.
4. Run `share-online.cmd`. It now connects your permanent address instead of a temporary link.

To limit who can even see the sign-in page, add a Cloudflare **Access** application for that hostname
(Zero Trust → Access → Applications) that allows only your colleagues' email addresses.

## Good to know

* The link works only while your PC is on, awake and connected. For 24×7 access use the hosted setup in `DEPLOYMENT.md`.
* Large Excel imports go through your PC's internet connection.
* After an update, close both windows and start `run-local.cmd` and `share-online.cmd` again; the website is rebuilt each time.
