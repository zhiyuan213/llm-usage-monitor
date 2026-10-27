"""Build with 0600/0700 source permissions, then exercise the non-root web image."""
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import time
import urllib.request
import uuid
import tempfile
import urllib.error

ROOT = Path(__file__).resolve().parent.parent
TAG = "metertrail-web:permissions-check"
NAME = "metertrail-web-check-" + uuid.uuid4().hex[:12]

def docker(*args, **kwargs):
    return subprocess.run(["docker", *args], check=True, timeout=kwargs.pop("timeout", 60), **kwargs)

def main():
    context = io.BytesIO()
    with tarfile.open(fileobj=context, mode="w:gz") as archive:
        for base, dirs, files in os.walk(ROOT / "frontend"):
            dirs[:] = sorted(d for d in dirs if d not in {"node_modules", "dist", ".git"})
            for name in sorted(dirs + files):
                source = Path(base) / name
                if source.is_symlink():
                    raise RuntimeError("Unexpected symlink in frontend context")
                item = archive.gettarinfo(str(source), arcname=source.relative_to(ROOT / "frontend").as_posix())
                item.mode = 0o700 if source.is_dir() else 0o600
                item.uid = item.gid = 0
                item.uname = item.gname = "root"
                if source.is_file():
                    with source.open("rb") as stream:
                        archive.addfile(item, stream)
                else:
                    archive.addfile(item)
    docker("build", "--progress=plain", "-t", TAG, "-", input=context.getvalue(), timeout=600)
    retained = tempfile.TemporaryDirectory(prefix="metertrail-assets-")
    retained_path = Path(retained.name)
    retained_path.chmod(0o755)
    (retained_path / "assets").mkdir()
    (retained_path / "assets").chmod(0o755)
    legacy = {"previous-release.js": b"window.previousRelease = true;", "previous-release.css": b"body { color: navy; }"}
    for name, content in legacy.items():
        (retained_path / "assets" / name).write_bytes(content)
        (retained_path / "assets" / name).chmod(0o644)
    try:
        docker("run", "-d", "--name", NAME, "--add-host", "api-blue:127.0.0.1",
               "-p", "127.0.0.1::8080", "-v",
               f"{ROOT / 'ops/nginx/upstream.conf'}:/etc/nginx/conf.d/upstream.conf:ro",
               "-v", f"{retained_path}:/usr/share/nginx/html/retained-assets:ro", TAG)
        uid = docker("exec", NAME, "id", "-u", capture_output=True, text=True).stdout.strip()
        if uid == "0":
            raise AssertionError("Web server must run as non-root")
        docker("exec", NAME, "nginx", "-t")
        port = docker("port", NAME, "8080/tcp", capture_output=True, text=True).stdout.strip().rsplit(":", 1)[1]
        origin = "http://127.0.0.1:" + port
        for attempt in range(30):
            try:
                with urllib.request.urlopen(origin + "/", timeout=2) as response:
                    html = response.read().decode()
                break
            except OSError:
                if attempt == 29:
                    raise
                time.sleep(0.5)
        import re
        assets = re.findall(r'(?:src|href)="(/(?:assets|fonts)/[^"<>]+)"', html)
        paths = ["/", "/terms", "/privacy", "/refunds", "/security", "/demo", "/tutorials", "/tutorials/nodejs", "/tutorials/python", "/tutorials/java", "/tutorials/rest", "/sitemap.xml", "/robots.txt", "/googlecc6b6e16e288c986.html", "/fonts/NotoSansSC-ui.woff2", *assets]
        for path in paths:
            with urllib.request.urlopen(origin + path, timeout=5) as response:
                content = response.read()
                if response.status != 200 or not content:
                    raise AssertionError("Unreadable public file: " + path)
                if path in {'/terms','/privacy','/refunds'}:
                    assert b'2026-10-27' in content and b'13428961230@163.com' in content, path
                    assert b'Deployment policy template' not in content, path
                if path == "/googlecc6b6e16e288c986.html" and content != (ROOT / "frontend/public" / path[1:]).read_bytes():
                    raise AssertionError("Google verification file changed")
                if path.startswith('/tutorials/') and b'Example request' not in content:
                    raise AssertionError("Tutorial initial HTML has no executable example")
        import gzip
        for path in ["/", "/index.html", "/security", "/app/workspace", "/login"]:
            with urllib.request.urlopen(origin + path, timeout=5) as response:
                assert response.headers.get('Cache-Control') == 'no-store', path
                assert response.headers.get('Content-Security-Policy'), path
        for name, expected in legacy.items():
            with urllib.request.urlopen(origin + '/assets/' + name, timeout=5) as response:
                assert response.read() == expected, name
                assert 'text/html' not in response.headers.get('Content-Type', ''), name
                assert 'max-age=' in response.headers.get('Cache-Control', ''), name
                assert response.headers.get('Content-Security-Policy'), name
        try:
            urllib.request.urlopen(origin + '/assets/not-a-release.js', timeout=5)
            raise AssertionError('Missing asset must not return SPA HTML')
        except urllib.error.HTTPError as error:
            assert error.code == 404
        compressed_sizes = {}
        for path in assets:
            if not path.endswith(('.js', '.css')):
                continue
            with urllib.request.urlopen(origin + path, timeout=5) as response:
                original = response.read()
            with urllib.request.urlopen(urllib.request.Request(origin + path, headers={'Accept-Encoding': 'gzip', 'Via': '1.1 acceptance-proxy'}), timeout=5) as response:
                compressed = response.read()
                assert response.headers.get('Content-Encoding') == 'gzip', path
                assert 'accept-encoding' in response.headers.get('Vary', '').lower(), path
                assert gzip.decompress(compressed) == original, path
                assert len(compressed) < len(original), path
                assert response.headers.get('Content-Security-Policy'), path
                compressed_sizes[path] = {'original': len(original), 'gzip': len(compressed)}
        assert compressed_sizes, 'No JS/CSS assets checked'
        for path in ["/app", "/app/workspace?test=1", "/login", "/pay?test=1"]:
            with urllib.request.urlopen(origin + path, timeout=5) as response:
                if "noindex" not in response.headers.get("X-Robots-Tag", ""):
                    raise AssertionError("Private URL missing noindex: " + path)
                if not response.headers.get("Content-Security-Policy"):
                    raise AssertionError("Security headers lost")
        print(json.dumps({"result": "passed", "runtime_uid": uid, "restrictive_context": "files 0600, directories 0700", "public_files_checked": paths, "private_headers_checked": 4, "static_compression": compressed_sizes}))
    finally:
        subprocess.run(["docker", "rm", "-f", NAME], check=False, timeout=30)
        retained.cleanup()

if __name__ == "__main__":
    main()
