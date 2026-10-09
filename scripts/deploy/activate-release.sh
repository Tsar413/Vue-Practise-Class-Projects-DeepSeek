#!/usr/bin/env bash
# Run on the server as root AFTER reviewing migration compatibility.
# Does not initialize, seed, drop or automatically roll back the database.
set -euo pipefail
release_sha="${1:?Usage: activate-release.sh <full-commit-sha>}"
[[ "$release_sha" =~ ^[a-f0-9]{40}$ ]] || exit 2
base=/opt/vue-practice
release="$base/releases/$release_sha"
test -f "$release/release.json"
python3 - "$release" "$release_sha" <<'PY'
import hashlib,json,sys
from pathlib import Path
root=Path(sys.argv[1]).resolve();m=json.loads((root/'release.json').read_text())
assert m['commit']==sys.argv[2]
for name,digest in m['files'].items():
    p=root/name
    assert p.resolve().is_relative_to(root) and p.is_file() and not p.is_symlink()
    assert hashlib.sha256(p.read_bytes()).hexdigest()==digest, 'Release integrity failure'
assert (root/'backend/app.jar').is_file() and (root/'frontend/index.html').is_file()
print('Release manifest verified')
PY
previous="$(readlink -f "$base/current")"
if [ "$previous" = "$release" ]; then
    echo 'Already active; no restart or database writes.'
    exit 0
fi
backup="$base/backups/release-$(date +%Y%m%dT%H%M%S)"
install -d -m 700 "$backup"
cp -a /etc/vue-practice /etc/nginx/sites-available/vue-practice /etc/systemd/system/vue-practice.service "$backup/"
systemctl stop vue-practice
# On failure keep writes stopped; inspect before choosing a code version.
mysqldump --single-transaction --routines --triggers --no-tablespaces vue_practice_public > "$backup/database.sql"
chmod 600 "$backup/database.sql"
tar -czf "$backup/images.tar.gz" -C "$base/data" repair-files teaching-files
MYSQL_HOME=/usr DB_SOCKET=/run/mysqld/mysqld.sock python3 "$release/scripts/migrate.py" --database vue_practice_public
ln -s "$previous" "$base/previous.next"
mv -Tf "$base/previous.next" "$base/previous"
ln -s "$release" "$base/current.next"
mv -Tf "$base/current.next" "$base/current"
nginx -t
systemctl start vue-practice
systemctl reload nginx
python3 - <<'PY'
import time,urllib.request,urllib.error
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
deadline=time.monotonic()+90
while time.monotonic()<deadline:
    try:
        with opener.open('http://127.0.0.1:8100/hello',timeout=3) as r:
            body=r.read().decode()
            if r.status==200 and body.strip()=='Hello Spring Boot!':break
    except (OSError,urllib.error.URLError):pass
    time.sleep(1)
else:raise SystemExit('Readiness failed. Keep backup; inspect logs and use documented code rollback.')
print('Backend ready; perform public acceptance separately')
PY
