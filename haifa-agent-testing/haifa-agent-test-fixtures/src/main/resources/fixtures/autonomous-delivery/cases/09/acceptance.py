#!/usr/bin/env python3

import hashlib
import json
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse


CASE_ROOT = Path(__file__).resolve().parent


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class Handler(BaseHTTPRequestHandler):
    first_requests = 0
    observed = []

    def do_GET(self):
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        cursor = query.get("cursor", [None])[0]
        type(self).observed.append((parsed.path, cursor))
        if parsed.path == "/items":
            if cursor is None:
                type(self).first_requests += 1
                if type(self).first_requests == 1:
                    self.send_response(429)
                    self.send_header("Retry-After", "0")
                    self.end_headers()
                    return
                self.respond(
                    {
                        "items": [{"id": "a", "v": 1}, {"id": "b", "v": "first"}],
                        "nextCursor": "page 2/+",
                    }
                )
            elif cursor == "page 2/+":
                self.respond(
                    {
                        "items": [{"id": "b", "v": "later"}, {"id": "陈"}],
                        "nextCursor": None,
                    }
                )
            else:
                self.respond({"error": "bad cursor"}, status=400)
        elif parsed.path == "/loop/items":
            self.respond({"items": [{"id": "x"}], "nextCursor": "again"})
        else:
            self.respond({"error": "missing"}, status=404)

    def respond(self, payload, status=200):
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        pass


def run(command, cwd, timeout=60):
    return subprocess.run(
        command, cwd=cwd, capture_output=True, text=True, timeout=timeout
    )


def main():
    workspace = Path(sys.argv[1]).resolve()
    checks = {}
    failures = []
    checks["testsUnchanged"] = digest(
        CASE_ROOT / "base-workspace/test/client.test.js"
    ) == digest(workspace / "test/client.test.js")
    checks["visibleTests"] = run(["node", "--test"], workspace).returncode == 0
    checks["diffCheck"] = run(["git", "diff", "--check"], workspace).returncode == 0

    Handler.first_requests = 0
    Handler.observed = []
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    base = f"http://127.0.0.1:{server.server_port}"
    try:
        completed = run(["node", "src/cli.js", base], workspace)
        try:
            data = json.loads(completed.stdout)
        except json.JSONDecodeError:
            data = None
        checks["paginationRetryDedupe"] = (
            completed.returncode == 0
            and data
            == [{"id": "a", "v": 1}, {"id": "b", "v": "first"}, {"id": "陈"}]
            and Handler.first_requests == 2
            and Handler.observed[-1] == ("/items", "page 2/+")
        )
        loop = run(["node", "src/cli.js", base + "/loop"], workspace)
        checks["cursorCycleRejected"] = (
            loop.returncode != 0
            and loop.stderr.strip()
            and len(Handler.observed) < 30
        )
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)

    script = r'''
import { fetchAllItems } from "./src/client.js";
let calls = 0;
const fake = async () => ({ok:true,status:200,json:async()=>({items:[{id:"ok"}],nextCursor:"next"})});
try {
  await fetchAllItems("https://x", {fetch: fake, maxPages: 1});
  process.exit(3);
} catch (error) {
  if (!String(error.message).toLowerCase().includes("page")) process.exit(4);
}
'''
    max_page = run(["node", "--input-type=module", "-e", script], workspace)
    checks["maxPages"] = max_page.returncode == 0

    for name, passed in checks.items():
        if not passed:
            failures.append(name)
    print(json.dumps({"case": "09-node-protocol", "passed": not failures, "checks": checks, "failures": failures}, ensure_ascii=False, sort_keys=True))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
