#!/bin/sh
# deb maintainer script (postinst) for the UOS variant — wired up via deb.afterInstall in
# electron-builder.uos.yml and passed to fpm as --after-install. Runs as root on install
# and upgrade, after dpkg has unpacked /usr/share/applications/infinia-uos.desktop.
#
# That unpack overwrites the arg-less menu shortcut dpkg-installed by earlier builds with
# the entry carrying `--no-sandbox` on the Electron command line (linux.executableArgs).
# Most systems refresh menu caches through the desktop-file-utils dpkg trigger; minimal
# UOS installs lack it, so refresh the database here to make the overwritten entry take
# effect immediately instead of after the next relogin.
set -e

if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database -q /usr/share/applications || true
fi
