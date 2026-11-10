# Notes for Claude

## Design

Match the owner's other apps (SuperAwesomeMagicalWeather, SuperAwesomeMagicalMusic): Lexend for
all text (`ui/theme/Theme.kt`), Material 3, cards with 20dp corners on `surfaceContainer` with the
title inside in `titleSmall`, transparent top bars over the gradient `Backdrop`, and the shared
components in `ui/components/Common.kt` rather than new one-offs.

Bold text is the owner's chosen default: `lexend(bold = true)` draws every weight 300 heavier
(capped at 700), exactly how the app looked under Android's Bold text setting (`BoldTextTest`
checks it pixel for pixel), and `MainActivity` switches the system's font weight adjustment off so
the look doesn't depend on that setting. Pick weights for the regular design; bold follows.

## Checks before pushing

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Look at the PNGs `ScreenshotTest` writes to `app/build/outputs/screenshots/` after UI changes.
Bump `versionCode` and `versionName` in `app/build.gradle.kts` and add a `CHANGELOG.md` section for
each release.

## Building in a Claude cloud sandbox

- Android SDK: unzip the command-line tools into e.g. `/home/user/android-sdk/cmdline-tools/latest`,
  run `sdkmanager "platforms;android-37.0" "build-tools;37.0.0" "platform-tools"`, and write
  `sdk.dir=…` to `local.properties` (git-ignored).
- If `repo.maven.apache.org` answers HTTP 429, mirror Maven Central with an init script in
  `~/.gradle/init.d/` pointing at `https://maven-central.storage-download.googleapis.com/maven2/`.
- Robolectric downloads Android jars itself: add
  `-Drobolectric.dependency.repo.url=https://maven-central.storage-download.googleapis.com/maven2`
  to `JAVA_TOOL_OPTIONS` before running the screenshot tests.
- A real Cockpit for `LiveCockpitTest`: `apt-get install -t noble-backports cockpit-ws
  cockpit-bridge cockpit-system`, create a user in the `sudo` group, set
  `[basic] Command = /usr/lib/cockpit/cockpit-session` in `/etc/cockpit/cockpit.conf` (there's no
  systemd to socket-activate it), and run `/usr/lib/cockpit/cockpit-ws --no-tls --port 9090
  --address 127.0.0.1`. systemd and journald don't run in the sandbox, so services and logs are
  covered by parser tests instead.
- Podman for `LiveWorkloadsTest`: `apt-get install podman runc busybox-static`; containers only
  run with `runtime = "runc"` and `cgroup_manager = "cgroupfs"` under `[engine]` in
  `/etc/containers/containers.conf`. Import a tiny image (tar a dir holding `/bin/busybox` plus
  `sh`/`sleep` symlinks, then `podman import` it) and start the API with
  `podman system service --time=0 unix:///run/podman/podman.sock`. There's no user session, so
  only system containers can be tested.
- libvirt: `apt-get install libvirt-daemon-system libvirt-dbus qemu-system-x86`; start the system
  bus (`dbus-daemon --system --fork`), `virtlogd -d`, `libvirtd -d`, define a small `type='qemu'`
  domain with `virsh define`, and run `/usr/sbin/libvirt-dbus --system`. QEMU runs without KVM.
- Follow the Red Hat plugins when touching Podman or VMs: cockpit-podman's `src/rest.ts` and
  `src/client.ts`, and cockpit-machines' `src/libvirtApi/`. The owner uses those plugins, so the
  app should show and do the same things.
