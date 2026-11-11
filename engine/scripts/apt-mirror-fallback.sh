#!/bin/sh
# Flowstead — point apt at a mirror outside ubuntu.com. SPDX-License-Identifier: GPL-3.0-or-later
#
# CI failed for hours on 11 Nov 2026 because the runners could not resolve Ubuntu's own mirrors (archive, security and
# azure.archive.ubuntu.com) while every other host resolved. The Docker image's two libraries and the browser test's
# system packages come from apt, so a build whose first apt attempt fails runs this and tries once more from the
# kernel.org mirror, which carries the same suites (security updates included) and signs with the same Ubuntu keys.
set -eu
MIRROR="${APT_FALLBACK_MIRROR:-http://mirrors.edge.kernel.org/ubuntu/}"
for f in /etc/apt/sources.list /etc/apt/sources.list.d/*.sources /etc/apt/sources.list.d/*.list; do
  [ -f "$f" ] || continue
  sed -i -E \
    -e "s#mirror\+file:/etc/apt/apt-mirrors\.txt#${MIRROR}#g" \
    -e "s#https?://([a-z0-9.-]+\.)?(archive|security)\.ubuntu\.com/ubuntu/?#${MIRROR}#g" "$f"
done
echo "apt sources now use ${MIRROR}"
