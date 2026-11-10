# Changelog

## [1.1.0]

- **Podman containers**, through the same Podman API and sockets as Cockpit's own Podman page
  (cockpit-podman): your rootless containers and, with administrative access, the system's.
  Grouped by pod, with live CPU and memory, search, and start, stop, restart, pause, resume and
  delete. Each container shows its image, command, ports, networks, volumes, health and live logs.
  Containers run as systemd services (Quadlets) are started, stopped and restarted through
  systemd, as cockpit-podman does.
- **Virtual machines**, through libvirt-dbus like Cockpit's Virtual machines page
  (cockpit-machines): system and session VMs with their OS, vCPUs, memory, disks, network and IP
  addresses, live CPU use, run, shut down, reboot, pause, resume, force shut down / reboot, and
  autostart.
- The Podman and VMs tabs appear only on servers that have them; the Overview gains a Workloads
  summary.

## [1.0.0]

First release.

- Connect to any server running Cockpit, with saved servers and optional encrypted saved passwords.
- Administrative access through sudo, two-factor sign-in prompts, and trust-on-first-use for
  Cockpit's self-signed certificate (with a warning if it changes).
- Overview: live CPU and memory, swap, load, temperature, storage, network and disk throughput,
  system details, restart and shut down.
- Services: filter, search, start/stop/restart/reload, enable/disable at boot, details and recent logs.
- Logs: the system journal by priority, current boot, search and live follow.
- Terminal: your login shell in a pseudo-terminal with an xterm-compatible screen and a mobile key row.
- Light and dark themes in Lexend, with optional wallpaper colors.
