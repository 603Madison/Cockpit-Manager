# Cockpit Manager

An Android app for Linux servers running [Cockpit](https://cockpit-project.org), the web console
that ships with Red Hat Enterprise Linux, Fedora, CentOS Stream, Rocky and Alma. It signs in the
same way Cockpit's own web page does and speaks Cockpit's protocol over its WebSocket, so there's
nothing to install on the server beyond Cockpit itself.

Built with Jetpack Compose and Material 3 in the same design language as the other apps
(SuperAwesomeMagicalWeather, SuperAwesomeMagicalMusic): Lexend throughout, rounded cards over a
soft gradient, light and dark themes, and optional wallpaper colors.

## What it does

- **Servers** – keep a list of servers; tap one to connect. Passwords can be remembered, encrypted
  with a key in the Android Keystore that never leaves the phone.
- **Overview** – host name, OS, uptime, live CPU and memory with sparklines, swap, load, CPU
  temperature, every mounted file system, network and disk throughput, hardware details, and
  restart / shut down.
- **Services** – every systemd service with its state and boot setting; filter by running, failed or
  enabled, search, and start, stop, restart, reload, enable or disable. Failures show systemd's own
  explanation. Each service has a detail page with PID, memory, CPU time and its recent log.
- **Logs** – the system journal by priority (errors, warnings, notices, all), current boot only,
  search, live follow, and older entries on demand.
- **Terminal** – your login shell in a real pseudo-terminal, with an xterm-compatible screen
  (colors, cursor addressing, the alternate screen), so `top`, `less`, `nano` and `vim` work. A key
  row gives Esc, Tab, Ctrl, arrows and Page Up/Down; hardware keyboards work too.

### Sign-in and security

- **Administrative access** uses your password with sudo, like "Reuse my password for privileged
  tasks" on Cockpit's login page. Without it the app still works, read-only where root is needed.
- **Two-factor prompts** (PAM conversations such as one-time codes) are asked for in the app.
- **Self-signed certificates**: Cockpit generates one on install. The first time, the app shows the
  certificate's SHA-256 fingerprint and asks before trusting it, then pins that exact certificate
  for that server. If it ever changes you're warned again. Certificates Android already trusts
  (public CAs, or a CA you installed on the phone) just work.
- **Plain `http://`** is only allowed to private-network addresses (LAN, `.local`, Tailscale), so a
  password is never sent unencrypted over the internet.
- On Android 17 and later the app asks for local-network access before connecting, since most
  Cockpit servers are on the LAN.

## Server setup

```sh
sudo dnf install cockpit            # already installed on most RHEL/Fedora servers
sudo systemctl enable --now cockpit.socket
sudo firewall-cmd --add-service=cockpit --permanent && sudo firewall-cmd --reload
```

Then add the server in the app as `server.local`, an IP address, or a full URL. A bare address
means `https://…:9090`; an explicit `https://host` (behind a reverse proxy) keeps port 443, and a
path such as `https://host/admin` is treated as Cockpit's `UrlRoot`. Cockpit refuses `root` by
default (`/etc/cockpit/disallowed-users`), so sign in as your own user.

To check a self-signed certificate's fingerprint on the server:

```sh
sudo openssl x509 -noout -fingerprint -sha256 -in /etc/cockpit/ws-certs.d/0-self-signed.cert
```

If Cockpit sits behind a reverse proxy, add the public address to `Origins` in
`/etc/cockpit/cockpit.conf`, or the WebSocket is refused.

## Building

Requires JDK 17+ and the Android SDK (platform 37, build tools 37.0.0).

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug APK is in `app/build/outputs/apk/debug/`. Release builds are signed when
`keystore.properties` (git-ignored) or the `RELEASE_KEYSTORE_*` environment variables are present;
the **Release** workflow (Actions → Release → Run workflow) builds and publishes a signed APK from
the `RELEASE_KEYSTORE_BASE64` / `RELEASE_KEYSTORE_PASSWORD` repository secrets.

### Tests

- Unit tests cover URL handling, sign-in (including two-factor), the WebSocket transport against a
  scripted server, metrics decoding, systemd/journal parsing and the terminal emulator.
- `ScreenshotTest` renders every screen with Robolectric to `app/build/outputs/screenshots/`.
- `LiveCockpitTest` runs the real client against a real Cockpit when these are set (skipped
  otherwise): sign-in, processes, D-Bus, live metrics, sudo, and an interactive shell.

  ```sh
  COCKPIT_LIVE_URL=http://127.0.0.1:9090 COCKPIT_LIVE_USER=tester COCKPIT_LIVE_PASSWORD=… \
    ./gradlew :app:testDebugUnitTest --tests '*LiveCockpitTest*'
  ```

## Layout

```
app/src/main/kotlin/app/cockpitmanager/
  cockpit/   Cockpit protocol: login, certificate trust, WebSocket transport, channels,
             processes, D-Bus, metrics
  system/    What the screens show: host facts, systemd services, the journal, power
  terminal/  The xterm-compatible screen model and the pty session
  data/      Server list and settings (DataStore), Keystore-encrypted passwords
  ui/        Compose screens, theme (Lexend, colors) and shared components
```

Lexend is licensed under the SIL Open Font License (`licenses/Lexend-OFL.txt`). This app isn't
affiliated with Red Hat or the Cockpit project.
