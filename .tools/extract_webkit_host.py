#!/usr/bin/env python3
"""Extract index.html + poops.html from itsPLK webkit-autoloader-host_*.py into out dir."""
import base64, re, sys, zipfile, io
from pathlib import Path

def main():
    if len(sys.argv) < 3:
        print(f"usage: {sys.argv[0]} host.py out_dir", file=sys.stderr)
        sys.exit(2)
    host_py = Path(sys.argv[1])
    out = Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    text = host_py.read_text(errors="replace")
    m = re.search(r"EMBEDDED_ZIP_B64\s*=\s*\(([\s\S]*?)\)\s*\n", text)
    if not m:
        print("EMBEDDED_ZIP_B64 not found", file=sys.stderr)
        sys.exit(1)
    parts = re.findall(r'"([A-Za-z0-9+/=]+)"', m.group(1))
    data = base64.b64decode("".join(parts))
    z = zipfile.ZipFile(io.BytesIO(data))
    for name in z.namelist():
        if name.endswith("/"):
            continue
        target = out / Path(name).name
        target.write_bytes(z.read(name))
        print("wrote", target, target.stat().st_size)
    # convenience alias
    idx = out / "index.html"
    if idx.exists() and not (out / "relapse.html").exists():
        (out / "relapse.html").write_bytes(idx.read_bytes())
        print("wrote", out / "relapse.html", "(alias)")

if __name__ == "__main__":
    main()
