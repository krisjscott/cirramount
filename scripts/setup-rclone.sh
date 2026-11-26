#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
version=v1.76.0
case "$(uname -m)" in
  x86_64) arch=amd64 ;;
  aarch64|arm64) arch=arm64 ;;
  *) echo 'Download the matching Linux build from https://rclone.org/downloads/' >&2; exit 1 ;;
esac
archive="rclone-${version}-linux-${arch}.zip"
scratch="$(mktemp -d)"
trap 'rm -rf -- "$scratch"' EXIT
curl --proto '=https' --fail --location --show-error "https://downloads.rclone.org/${version}/${archive}" -o "$scratch/$archive"
curl --proto '=https' --fail --location --show-error "https://downloads.rclone.org/${version}/SHA256SUMS" -o "$scratch/SHA256SUMS"
python3 - "$scratch" "$archive" "$version" "$arch" <<'PY'
import hashlib, pathlib, sys, zipfile
scratch, name, version, arch = sys.argv[1:]
root = pathlib.Path(scratch)
digest = hashlib.sha256((root / name).read_bytes()).hexdigest()
lines = [line.split() for line in (root / 'SHA256SUMS').read_text().splitlines()]
if not any(len(parts) == 2 and parts[0] == digest and parts[1].lstrip('*') == name for parts in lines):
    raise SystemExit('rclone archive checksum did not match. Nothing was installed.')
target = pathlib.Path('tools/rclone')
target.parent.mkdir(exist_ok=True)
with zipfile.ZipFile(root / name) as archive:
    payload = archive.read(f'rclone-{version}-linux-{arch}/rclone')
temporary = target.with_suffix('.new')
temporary.write_bytes(payload)
temporary.chmod(0o755)
temporary.replace(target)
print(f'Installed verified rclone {version} for Linux {arch}.')
PY
