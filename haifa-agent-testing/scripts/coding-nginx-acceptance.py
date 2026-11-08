#!/usr/bin/env python3
"""Exercise the packed Coding Web through a disposable HTTPS Nginx and real Host."""

import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import secrets
import shutil
import socket
import ssl
import subprocess
import threading
import time
from contextlib import ExitStack
from http.client import HTTPSConnection, HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from playwright.sync_api import sync_playwright

spec = importlib.util.spec_from_file_location(
    "coding_b2", Path(__file__).with_name("coding-sdk-b2-acceptance.py")
)
b2 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b2)
TEMPLATES = b2.ROOT / "docs/applications/coding-nginx"


def port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def render(template, destination, values):
    source = (TEMPLATES / template).read_text(encoding="utf-8")
    for key, value in values.items():
        source = source.replace("{{" + key + "}}", str(value))
    b2.require("{{" not in source, "UNRESOLVED_NGINX_TEMPLATE")
    destination.write_text(source, encoding="utf-8")


def run(command, **kwargs):
    result = subprocess.run(command, capture_output=True, timeout=30, **kwargs)
    b2.require(result.returncode == 0, "FIXTURE_COMMAND_FAILED")
    return result.stdout


def stream_timing(page):
    return page.evaluate("acceptanceStreams.map(({beats,frames}) => ({beats,frames}))")


def digest_assets(root):
    return {p.relative_to(root).as_posix(): b2.sha(p) for p in sorted(root.rglob("*")) if p.is_file()}


class Gateway:
    def __init__(self):
        self.cookie = secrets.token_urlsafe(32)
        self.expiry = time.monotonic() + 300
        gateway = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                authorized = (
                    self.path == "/verify"
                    and time.monotonic() < gateway.expiry
                    and self.headers.get("Cookie") == "coding_gateway=" + gateway.cookie
                    and self.headers.get("Authorization") is None
                )
                self.send_response(204 if authorized else 401)
                self.end_headers()

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


def write_stub_config(root, stub):
    config = root / "trusted.yaml"
    database = (root / "unused-owner-path.sqlite").as_posix().replace("'", "''")
    config.write_text(f"""models:
  default: external-fixture
  providers:
    - id: deepseek
      displayName: Local acceptance Stub
      endpoint: http://127.0.0.1:{stub.server.server_port}/v1
      credentialRef: env://HFA_FIXTURE_KEY
      nativeStreaming: false
      apiBindings:
        - style: openai-chat-completions
          dialect: deepseek-openai-chat
      models:
        - id: external-fixture
          providerModelId: deepseek-flash
          style: openai-chat-completions
          capabilities: [TEXT_CHAT, TOOL_CALLING]
          contextWindow: 32768
          maxOutputTokens: 1024
tools:
  enabled: [file_stat, file_read, file_create, file_write, ask_clarification]
approval:
  mode: ask
  threshold: low
persistence:
  mode: SQLITE
  databasePath: '{database}'
  protection: NONE
execution:
  provider: host-guarded
  shell: auto
""", encoding="utf-8")
    return config


def stage_nginx(root, args, external, listen_port, host_port, proxy_proof, gateway):
    root.joinpath("logs").mkdir()
    root.joinpath("temp").mkdir()
    cert = root / "local.crt"
    key = root / "local.key"
    run([args.openssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
         "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost",
         "-keyout", str(key), "-out", str(cert)])
    public_key = run([args.openssl, "x509", "-in", str(cert), "-pubkey", "-noout"])
    der = run([args.openssl, "pkey", "-pubin", "-outform", "DER"], input=public_key)
    pin = base64.b64encode(hashlib.sha256(der).digest()).decode("ascii")
    proof_include = root / "proxy-proof.conf"
    proof_include.write_text('proxy_set_header X-Haifa-Proxy-Proof "' + proxy_proof + '";\n', encoding="ascii")
    gateway_include = ""
    if gateway:
        render("gateway-session.conf.template", root / "gateway.conf", {"AUTH_PORT": gateway.server.server_port})
        gateway_include = "include gateway.conf;"
    render("nginx.conf.template", root / "nginx.conf", {
        "LISTEN": f"127.0.0.1:{listen_port}",
        "PUBLIC_AUTHORITY": external.removeprefix("https://"),
        "PUBLIC_HOST": "localhost",
        "CERTIFICATE": cert.as_posix(),
        "CERTIFICATE_KEY": key.as_posix(),
        "HOST_PORT": host_port,
        "PROXY_PROOF_INCLUDE": proof_include.as_posix(),
        "GATEWAY_SESSION_INCLUDE": gateway_include,
    })
    command = [args.nginx, "-p", root.as_posix() + "/", "-c", "nginx.conf"]
    run([*command, "-t"])
    return command, cert, pin


def stop_nginx(command, process):
    if process.poll() is None:
        subprocess.run([*command, "-s", "stop"], capture_output=True, timeout=10)
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.terminate()
            process.wait(timeout=5)


def status(client_type, hostname, target_port, path, headers=None, body=None, **kwargs):
    client = client_type(hostname, target_port, timeout=6, **kwargs)
    try:
        client.request("GET" if body is None else "POST", path, body=body, headers=headers or {})
        return client.getresponse().status
    finally:
        client.close()


WATCH_STREAMS = """(() => {
  const request = window.fetch.bind(window);
  const streams = new Set();
  window.disconnectAcceptanceStreams = () => {
    for (const stream of streams) stream.abort();
  };
  window.acceptanceStreams = [];
  window.fetch = async (...args) => {
    const isEventStream = String(args[0]).includes('/browser/v1/events?');
    const controller = isEventStream ? new AbortController() : null;
    if (controller) {
      streams.add(controller);
      const signal = args[1]?.signal;
      args[1] = {...args[1], signal: signal
        ? AbortSignal.any([signal, controller.signal]) : controller.signal};
    }
    if (String(args[0]).includes('/browser/v1/rpc')) {
      const rpc = JSON.parse(args[1].body);
      if (rpc.method === 'product/command' && rpc.params.command.operation === 'cancel')
        window.cancelledTarget = rpc.params.command.target;
    }
    let response;
    try {
      response = await request(...args);
    } catch (error) {
      streams.delete(controller);
      throw error;
    }
    if (window.dropAcceptedCommand && String(args[0]).includes('/browser/v1/rpc')) {
      const body = JSON.parse(args[1].body);
      if (body.method === 'product/command' && body.params.command.operation === 'submit') {
        window.dropAcceptedCommand = false;
        window.acceptedCommandStatus = response.status;
        await response.body.cancel();
        throw new TypeError('Fixture discarded the command receipt');
      }
    }
    if (!controller || !response.ok) {
      streams.delete(controller);
      return response;
    }
    const state = {beats: [], frames: [], closed: false};
    window.acceptanceStreams.push(state);
    const reader = response.clone().body.getReader();
    void (async () => {
      const decoder = new TextDecoder();
      let pending = '';
      try {
        for (;;) {
          const part = await reader.read();
          if (part.done) break;
          pending += decoder.decode(part.value, {stream:true});
          let end;
          while ((end = pending.indexOf('\\n\\n')) >= 0) {
            const frame = pending.slice(0, end);
            const samples = frame.startsWith(':') ? state.beats : state.frames;
            if (samples.length < 100) samples.push(performance.now());
            pending = pending.slice(end + 2);
          }
        }
      } catch {} finally {
        state.closed = true;
        streams.delete(controller);
        reader.releaseLock();
      }
    })();
    return response;
  };
})();"""


def pair(page, owner):
    challenge = owner.command("pair", "pairing")
    page.locator("#pair-code").fill(challenge["challenge"])
    page.locator("#pair-button").click()
    page.locator("#pairing").wait_for(state="hidden")


def await_page(page, expression, timeout=20):
    # The deployed CSP prohibits wait_for_function's in-page eval.
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if page.evaluate(expression):
            return
        time.sleep(0.05)
    raise RuntimeError("BROWSER_CONDITION_TIMEOUT")


def browser_flow(page, owner, stub, root, evidence, nginx):
    evidence["browserStage"] = "pair"
    pair(page, owner)
    evidence["browserStage"] = "approval"
    page.locator("#draft").fill("Create the acceptance fixture after explicit approval.")
    page.locator("#send").click()
    interaction = page.locator(".interaction-form")
    approve = interaction.get_by_role("button", name="批准执行", exact=True)
    approve.wait_for()
    run([*nginx, "-s", "reload"])
    try:
        await_page(page, "acceptanceStreams.some(s => s.beats.length >= 9 && !s.closed)", timeout=45)
    finally:
        evidence["beforeApprovalTimingMs"] = stream_timing(page)
    evidence["heartbeatSpanMs"] = max(
        (stream["beats"][-1] - stream["beats"][0] for stream in evidence["beforeApprovalTimingMs"] if stream["beats"]),
        default=0,
    )
    b2.require(evidence["heartbeatSpanMs"] >= 15000, "IDLE_STREAM_DID_NOT_CROSS_TIMEOUT")
    b2.require(not (root / "workspace/approved.txt").exists(), "EFFECT_BEFORE_APPROVAL")
    interaction.get_by_role("checkbox", name="我已核对本次工具操作的内容和作用范围").check()
    approve.click()
    page.get_by_text("external-safe-completion", exact=True).wait_for()
    b2.require((root / "workspace/approved.txt").read_text() == "external-approved-content", "APPROVAL_EFFECT")
    evidence["firstEventBeforeCompletion"] = any(
        stream["frames"] for stream in evidence["beforeApprovalTimingMs"]
    )
    b2.require(evidence["firstEventBeforeCompletion"], "NO_INCREMENTAL_EVENTS")
    evidence["streamTimingMs"] = stream_timing(page)
    evidence["checks"].append("packed-web-approval-incremental-final")
    evidence["checks"].append("nginx-graceful-reload-during-open-stream")

    evidence["browserStage"] = "reload-history"
    page.reload()
    page.locator("#pair-code").wait_for()
    b2.require(page.evaluate("localStorage.length === 0 && sessionStorage.length === 0"), "PERSISTED_BROWSER_GRANT")
    pair(page, owner)
    page.locator("#session-list button").first.click()
    page.get_by_text("external-safe-completion", exact=True).wait_for()
    evidence["checks"].append("reload-repair-history")

    if stub.calls != 2:
        raise RuntimeError("UNEXPECTED_STUB_CALL_COUNT")

    evidence["browserStage"] = "unknown-receipt"
    page.evaluate("window.dropAcceptedCommand = true")
    page.locator("#draft").fill("Run one synthetic follow-up with a dropped receipt.")
    page.locator("#send").click()
    page.get_by_text("操作结果未知。任务可能已被接收，请先核对权威会话和历史；本页不会自动重发。",
                     exact=True).wait_for()
    b2.require(page.evaluate("window.acceptedCommandStatus") == 200, "COMMAND_NOT_ACCEPTED")
    b2.require(page.get_by_text("external-safe-completion", exact=True).count() >= 1, "NO_HISTORY")
    page.get_by_text("external-safe-completion", exact=True).nth(1).wait_for()
    b2.require(stub.calls == 3, "UNKNOWN_COMMAND_REPLAY")
    evidence["checks"].append("dropped-accepted-receipt-unknown-no-replay")


def gateway_expiry(page, owner, gateway, evidence):
    bound = initialize_probe(page, owner)
    subscription = subscribe_probe(page, get_session(page))
    sub_id = subscription["subscriptionId"]
    page.evaluate("(id) => probe.attach(id)", sub_id)
    await_page(page, "probe.stream()?.bytes > 0")
    before = page.evaluate("probe.stream().bytes")
    gateway.expiry = time.monotonic()
    denied = page.evaluate("""async id => {
        const rpc = await fetch('/browser/v1/rpc',{method:'POST',credentials:'same-origin',
            headers:{'Content-Type':'application/json'},body:'{}'});
        const sse = await fetch('/browser/v1/events?subscriptionId='+encodeURIComponent(id),
            {credentials:'same-origin'});
        return [rpc.status,sse.status,rpc.headers.get('X-Haifa-Gateway-Session'),
            sse.headers.get('X-Haifa-Gateway-Session')];
    }""", sub_id)
    b2.require(denied == [401, 401, "required", "required"], "GATEWAY_EXPIRY")
    await_page(page, f"probe.stream()?.bytes > {before} && !probe.stream().closed")
    page.evaluate("window.dispatchEvent(new Event('online'))")
    page.get_by_text("网关会话已失效。请重新登录网关，再重新配对并核对任务状态。", exact=True).wait_for()
    owner.command("revoke " + bound["clientId"], "revoked")
    b2.await_stream_closed(page, 20)
    evidence["checks"].append("gateway-expiry-denies-new-streams-host-revoke-closes-existing")


def initialize_probe(page, owner):
    page.evaluate(b2.PROBE)
    bound = page.evaluate("(proof) => probe.pair(proof)", owner.command("pair", "pairing"))
    initialized = b2.result(page.evaluate("""() => probe.rpc("initialize", {protocolVersion:"1",
        clientInfo:{name:"nginx-acceptance-probe",version:"1"}, optionalCapabilities:
        ["coding-product-v1","session-presentation-v1","session-consent-v1"]})"""))
    b2.require(initialized["clientId"] == bound["clientId"] and initialized["clientSource"] == "browser",
               "BROWSER_BINDING")
    return bound


def subscribe_probe(page, session_id):
    return b2.result(page.evaluate("(id) => probe.rpc('presentation/subscribe',{sessionId:id})", session_id))


def current_run(probe, session):
    subscription = subscribe_probe(probe, session)
    b2.result(probe.evaluate("(id) => probe.rpc('presentation/close',{subscriptionId:id})",
                             subscription["subscriptionId"]))
    return subscription["snapshot"]["session"]["run"]


def run_fact(probe, session, run_id):
    return b2.result(probe.evaluate("""([sessionId,runId]) => probe.rpc('product/query',{
        operation:'run',target:{projectId:null,sessionId,runId,interactionId:null,targetRevision:null},
        search:null,limit:1,executionId:null})""", [session, run_id]))["run"]


def await_run(probe, session, run_id, status):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        fact = run_fact(probe, session, run_id)
        if fact["status"] == status:
            return fact
        time.sleep(0.1)
    raise RuntimeError("RUN_STATE_TIMEOUT_" + status)


def clarification_request(_):
    arguments = {"questions": [{"header": "Fixture target", "question": "Which target should be built?",
                                "multiple": False, "custom": False,
                                "options": [{"label": "Portable target"}, {"label": "Local target"}]}]}
    return {"role": "assistant", "content": None, "tool_calls": [
        {"id": "fixture-clarification", "type": "function",
         "function": {"name": "ask_clarification", "arguments": json.dumps(arguments)}}]}


def answer_completion(request):
    b2.require(any(message.get("role") == "tool" and "Portable target" in str(message.get("content"))
                   for message in request["messages"]), "CLARIFICATION_ANSWER_NOT_DELIVERED")
    return {"role": "assistant", "content": "fixture-completed-after-clarification"}


def browser_resilience(browser, context, page, external, owner, stub, evidence):
    page.reload()
    pair(page, owner)
    page.locator("#session-list button").first.click()
    page.get_by_text("external-safe-completion", exact=True).nth(1).wait_for()

    with ExitStack() as stack:
        observer = browser.new_context(storage_state=context.storage_state())
        stack.callback(observer.close)
        probe = observer.new_page()
        probe.goto(external + "/browser/")
        initialize_probe(probe, owner)
        session = get_session(probe)
        sub = subscribe_probe(probe, session)["subscriptionId"]
        probe.evaluate("(id) => probe.attach(id, true)", sub)
        paused_at = time.monotonic()

        evidence["browserStage"] = "clarification"
        stub.respond = clarification_request
        page.locator("#draft").fill("Ask the operator which fixture target should be built.")
        page.locator("#send").click()
        interaction = page.locator(".interaction-form").filter(has_text="Which target should be built?")
        interaction.get_by_role("radio", name="Portable target", exact=True).check()
        clarified = current_run(probe, session)["id"]
        stub.respond = answer_completion
        stub.mode = "hold"
        interaction.get_by_role("button", name="提交答复", exact=True).click()
        b2.require(stub.entered.wait(12), "CLARIFICATION_NOT_RESUMED")

        evidence["browserStage"] = "offline-and-slow-observer"
        await_page(page, "acceptanceStreams.some(s => !s.closed && s.frames.length)")
        stream_count = page.evaluate("acceptanceStreams.length")
        context.set_offline(True)
        try:
            page.evaluate("disconnectAcceptanceStreams()")
            await_page(page, "acceptanceStreams.every(s => s.closed)")
            b2.require(probe.evaluate("probe.stream().bytes === 0 && !probe.stream().closed"),
                       "SLOW_OBSERVER_DID_NOT_PAUSE")
            stub.release.set()
            completed = await_run(probe, session, clarified, "COMPLETED")
            b2.require(completed["output"] == "fixture-completed-after-clarification" and stub.calls == 5,
                       "OFFLINE_RUN_COMPLETION_OR_REPLAY")
            time.sleep(max(0, 4 - (time.monotonic() - paused_at)))
            probe.evaluate("probe.resume()")
            await_page(probe, "probe.stream().bytes > 0 && !probe.stream().closed")
            evidence["pausedReaderMs"] = round((time.monotonic() - paused_at) * 1000)
        finally:
            context.set_offline(False)
        page.get_by_text("fixture-completed-after-clarification", exact=True).wait_for()
        await_page(page, f"acceptanceStreams.slice({stream_count}).some(s => !s.closed)")
        evidence["checks"].extend(("clarification-resumed-with-selected-answer",
                                   "offline-run-completed-and-browser-resubscribed",
                                   "paused-sse-reader-resumed-without-blocking-run"))

        evidence["browserStage"] = "exact-cancel"
        stub.respond = lambda _: {"role": "assistant", "content": "fixture-cancelled-run-should-not-complete"}
        stub.entered.clear()
        stub.release.clear()
        page.locator("#draft").fill("Start a separate held fixture run for exact cancellation.")
        page.locator("#send").click()
        b2.require(stub.entered.wait(12), "CANCEL_RUN_NOT_STARTED")
        active = current_run(probe, session)
        b2.require(active["id"] != clarified and active["status"] == "RUNNING", "CANCEL_RUN_WRONG_TARGET")
        page.locator("#cancel-run").click()
        await_run(probe, session, active["id"], "CANCELLED")
        target = page.evaluate("window.cancelledTarget")
        b2.require(target["runId"] == active["id"] and target["sessionId"] == session
                   and target["targetRevision"] is not None, "CANCEL_TARGET_NOT_EXACT")
        stub.release.set()
        b2.require(run_fact(probe, session, clarified)["status"] == "COMPLETED" and stub.calls == 6,
                   "CANCEL_AFFECTED_PREVIOUS_RUN_OR_REPLAYED")
        b2.result(probe.evaluate("() => probe.rpc('client/close')"))
        evidence["checks"].append("web-cancel-exact-run-preserved-previous-result")


def idle_timeout(context, external, owner, nginx, listen_port, tls, gateway, evidence):
    config = Path(nginx[2]) / "nginx.conf"
    original = config.read_text(encoding="utf-8")
    # Fault profile: force upstream silence to exceed the read budget between Host heartbeats.
    fault = original.replace("proxy_read_timeout 15s;", "proxy_read_timeout 1s;")
    fault = fault.replace("        server_name localhost;", "        server_name localhost;\n"
                          "        add_header X-Haifa-Acceptance-Idle on always;")
    b2.require(fault != original, "IDLE_PROFILE_UNCHANGED")
    headers = {"Connection": "close"}
    if gateway:
        headers["Cookie"] = "coding_gateway=" + gateway.cookie

    def load(content, marker):
        config.write_text(content, encoding="utf-8")
        run([*nginx, "-t"])
        run([*nginx, "-s", "reload"])
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            client = HTTPSConnection("localhost", listen_port, context=tls, timeout=3)
            try:
                client.request("GET", "/browser/v1/info", headers=headers)
                response = client.getresponse()
                if response.status == 200 and response.getheader("X-Haifa-Acceptance-Idle") == marker:
                    return
            finally:
                client.close()
            time.sleep(0.1)
        raise RuntimeError("IDLE_PROFILE_RELOAD_TIMEOUT")

    probe = context.new_page()
    try:
        load(fault, "on")
        probe.goto(external + "/browser/")
        initialize_probe(probe, owner)
        sub_id = subscribe_probe(probe, get_session(probe))["subscriptionId"]
        start = time.monotonic()
        probe.evaluate("(id) => probe.attach(id)", sub_id)
        b2.await_stream_closed(probe, 5)
        elapsed = time.monotonic() - start
        b2.require(0.65 <= elapsed <= 5 and probe.evaluate("probe.stream().failed"),
                   "IDLE_TIMEOUT_DID_NOT_ABORT_SSE")
        b2.result(probe.evaluate("() => probe.rpc('client/close')"))
        evidence["idleCloseMs"] = round(elapsed * 1000)
        evidence["checks"].append("nginx-idle-read-timeout-aborts-sse-with-live-grant")
    finally:
        probe.close()
        load(original, None)


def host_grants(context, page, external, owner, restart, host_port, evidence):
    first = initialize_probe(page, owner)
    session = get_session(page)
    sub_id = subscribe_probe(page, session)["subscriptionId"]
    page.evaluate("(id) => probe.attach(id)", sub_id)
    b2.require(status(HTTPConnection, "127.0.0.1", host_port, f"/v1/events?subscriptionId={sub_id}",
                      {"X-CA-Client-ID": first["clientId"]}) in (403, 409), "NATIVE_BROWSER_SUBSCRIPTION")
    second = context.new_page()
    second.goto(external + "/browser/")
    initialize_probe(second, owner)
    code = second.evaluate("""async id => {
        const request = window.fetch;
        let status;
        window.fetch = async (...args) => {
            const response = await request(...args);
            status = response.status;
            return response;
        };
        try { await probe.attach(id); } catch {} finally { window.fetch = request; }
        return status;
    }""", sub_id)
    b2.require(code in (403, 404, 409), "CROSS_CLIENT_SUBSCRIPTION")
    b2.result(second.evaluate("() => probe.rpc('client/renew')"))
    owner.command("revoke " + first["clientId"], "revoked")
    b2.await_stream_closed(page, 8)
    b2.require(page.evaluate("() => probe.rpc('client/renew')")["status"] == 403, "REVOKED_RENEW")
    evidence["checks"].append("cross-client-and-native-denial-host-revoke")

    owner = restart()
    b2.require(second.evaluate("() => probe.rpc('client/renew')")["status"] == 403, "OLD_HOST_GRANT")
    second.close()
    fresh = initialize_probe(page, owner)
    b2.require(first["hostIncarnation"] != fresh["hostIncarnation"], "HOST_RESTART_INCARNATION")
    subscription = subscribe_probe(page, session)
    b2.require(subscription["snapshot"]["consent"]["state"] == "ASK", "CONSENT_AFTER_RESTART")
    page.evaluate("(id) => probe.attach(id)", subscription["subscriptionId"])
    b2.await_stream_closed(page, 15)
    b2.require(page.evaluate("() => probe.rpc('client/renew')")["status"] == 403, "EXPIRED_RENEW")
    evidence["checks"].append("host-restart-history-default-consent-absolute-grant-expiry")


def get_session(page):
    reply = b2.result(page.evaluate("""() => probe.rpc("product/query", {
        operation:"sessions",target:{projectId:null,sessionId:null,runId:null,interactionId:null,
        targetRevision:null},search:null,limit:4,executionId:null})"""))
    return reply["sessions"][0]["sessionId"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("run-root", "assets", "preview-repository"):
        parser.add_argument("--" + name, type=Path, required=True)
    for name in ("version", "expected-sha", "chromium", "nginx", "openssl"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--gateway", action="store_true")
    parser.add_argument("--classpath-file", type=Path,
                        help="Reuse a previously built external Host for this exact source and preview manifest")
    args = parser.parse_args()
    root = args.run_root.resolve()
    b2.require(not root.exists() and not root.is_relative_to(b2.ROOT), "NEW_EXTERNAL_RUN_ROOT_REQUIRED")
    revision = run(["git", "rev-parse", "HEAD"], cwd=b2.ROOT).decode().strip()
    b2.require(revision == args.expected_sha, "SOURCE_PROVENANCE")
    b2.require(not run(["git", "status", "--porcelain", "--untracked-files=no"], cwd=b2.ROOT),
               "DIRTY_TRACKED_SOURCE")
    manifest = (args.preview_repository / "io/haifa/haifa-coding-host" / args.version
                / "haifa-coding-product-preview-manifest.json").resolve()
    release = json.loads(manifest.read_text(encoding="utf-8"))
    b2.require(release["state"] == "complete" and release["sourceSha"] == revision, "PREVIEW_PROVENANCE")
    for entry in release["artifacts"]:
        artifact = args.preview_repository / entry["relativePath"]
        b2.require(b2.sha(artifact) == entry["sha256"], "PREVIEW_HASH")
    root.mkdir(parents=True, mode=0o700)
    evidence = {"status": "FAIL", "sourceSha": revision, "gateway": args.gateway,
                "mavenManifestSha256": b2.sha(manifest), "platform": platform.system(), "checks": []}
    stage = "prepare"
    try:
        root.joinpath("workspace").mkdir()
        shutil.copytree(args.assets, root / "assets")
        evidence["assets"] = digest_assets(root / "assets")
        if args.classpath_file:
            classpath = json.loads(args.classpath_file.read_text(encoding="utf-8"))
            b2.require(classpath["sourceSha"] == revision
                       and classpath["manifestSha256"] == evidence["mavenManifestSha256"],
                       "EXTERNAL_CLASSPATH_PROVENANCE")
            for jar in classpath["jars"]:
                b2.require(b2.sha(Path(jar["path"])) == jar["sha256"], "EXTERNAL_CLASSPATH_HASH")
            cp = os.pathsep.join(jar["path"] for jar in classpath["jars"])
        else:
            cp, _ = b2.build_example("host", root, args.preview_repository.resolve(), args.version)
            classpath = {"sourceSha": revision, "manifestSha256": evidence["mavenManifestSha256"],
                         "jars": [{"path": path, "sha256": b2.sha(Path(path))} for path in cp.split(os.pathsep)]}
            (root / "host-classpath.json").write_text(json.dumps(classpath), encoding="utf-8")
        listen_port, host_port = port(), port()
        while listen_port == host_port:
            host_port = port()
        external = f"https://localhost:{listen_port}"
        proof = secrets.token_hex(32)
        with ExitStack() as stack:
            for name in ("proxy-proof.txt", "proxy-proof.conf", "local.key"):
                stack.callback(root.joinpath(name).unlink, missing_ok=True)
            gateway = Gateway() if args.gateway else None
            if gateway:
                stack.callback(gateway.close)
            nginx, cert, pin = stage_nginx(root, args, external, listen_port, host_port, proof, gateway)
            evidence["nginx"] = subprocess.run([args.nginx, "-v"], capture_output=True, check=True).stderr.decode().strip()
            nginx_process = subprocess.Popen([*nginx, "-g", "daemon off;"], stdout=subprocess.DEVNULL,
                                             stderr=subprocess.DEVNULL)
            stack.callback(stop_nginx, nginx, nginx_process)
            stub = b2.Stub(root / "workspace")
            stack.callback(stub.close)
            root.joinpath("proxy-proof.txt").write_text(proof + "\n", encoding="ascii")
            os.environ.update(HAIFA_CODING_EXTERNAL_ORIGIN=external,
                              HAIFA_CODING_PROXY_PROOF_FILE=str(root / "proxy-proof.txt"))
            config = write_stub_config(root, stub)
            owner = b2.Owner(cp, root, config, host_port, 120)
            stack.callback(owner.close)
            def restart():
                owner.close()
                fresh = b2.Owner(cp, root, config, host_port, 8)
                stack.callback(fresh.close)
                return fresh
            stage = "https-route"
            tls = ssl.create_default_context(cafile=str(cert))
            for path in ("/v1/rpc", "/v1/events", "/health", "/__haifa_gateway", "/browser/v1/unknown"):
                b2.require(status(HTTPSConnection, "localhost", listen_port, path, context=tls) == 404, "PUBLIC_ROUTE")
            b2.require(status(HTTPSConnection, "localhost", listen_port, "/browser/",
                              {"Host": "wrong.example"}, context=tls) == 421, "WRONG_EXTERNAL_HOST")
            challenge = owner.command("pair", "pairing")
            wrong_origin = {"Origin": "https://evil.example", "X-Forwarded-Host": f"localhost:{listen_port}",
                            "X-Forwarded-Proto": "https", "X-Haifa-Proxy-Proof": "forged",
                            "Content-Type": "application/json"}
            if gateway:
                wrong_origin["Cookie"] = "coding_gateway=" + gateway.cookie
            b2.require(status(HTTPSConnection, "localhost", listen_port, "/browser/v1/pair", wrong_origin,
                              json.dumps({"challenge": challenge["challenge"],
                                          "hostIncarnation": challenge["hostIncarnation"]}), context=tls) == 403,
                       "FORWARDED_ORIGIN_BYPASS")
            b2.require(status(HTTPConnection, "127.0.0.1", host_port, "/browser/v1/info",
                              {"X-Forwarded-Host": f"localhost:{listen_port}", "X-Haifa-Proxy-Proof": "forged"}) == 403,
                       "DIRECT_BROWSER_UPSTREAM")
            evidence["checks"].append("tls-and-restricted-routes")
            stage = "browser"
            with sync_playwright() as playwright:
                browser = playwright.chromium.launch(executable_path=args.chromium, headless=True,
                    args=["--no-sandbox", "--no-proxy-server", "--ignore-certificate-errors-spki-list=" + pin])
                evidence["browser"] = browser.version
                context = browser.new_context()
                if gateway:
                    context.add_cookies([{"name":"coding_gateway","value":gateway.cookie,"url":external,
                                          "secure":True,"httpOnly":True,"sameSite":"Strict"}])
                page = context.new_page()
                page.set_default_timeout(20000)
                page.add_init_script(WATCH_STREAMS)
                page.goto(external + "/browser/")
                b2.require(page.evaluate("isSecureContext"), "NO_SECURE_BROWSER_CONTEXT")
                browser_flow(page, owner, stub, root, evidence, nginx)
                stage = "browser-resilience"
                browser_resilience(browser, context, page, external, owner, stub, evidence)
                stage = "nginx-idle-timeout"
                idle_timeout(context, external, owner, nginx, listen_port, tls, gateway, evidence)
                if gateway:
                    stage = "gateway-expiry"
                    gateway_expiry(page, owner, gateway, evidence)
                else:
                    stage = "host-grants"
                    host_grants(context, page, external, owner, restart, host_port, evidence)
                browser.close()
            evidence.pop("browserStage", None)
        evidence["status"] = "PASS"
    except Exception as failure:
        evidence["failure"] = type(failure).__name__
        if isinstance(failure, RuntimeError) and str(failure).replace("_", "").isalnum():
            evidence["failureCode"] = str(failure)
        evidence["stage"] = stage
    finally:
        (root / "manifest.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(f'CODING_NGINX={evidence["status"]} evidence={root / "manifest.json"}')
    return 0 if evidence["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
