#!/usr/bin/env python3
"""Focused external thin-JAR + actual Chromium Coding Host acceptance; synthetic loopback Provider only.

Requires Java/Maven, Playwright Python and an installed Chromium. Run roots must be new and outside
this checkout. Owner pairing IPC and page-memory grants are never recorded in evidence or logs.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import shutil
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
PROBE = r'''(() => {
 let grant = null, current = null, stream = null;
 async function rpc(method, params={}) {
  const r=await fetch('/browser/v1/rpc',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+(grant||'')},body:JSON.stringify({jsonrpc:'2.0',id:1,method,params})});
  const body=await r.json(); return {status:r.status,body};
 }
 window.probe={
  async pair(value) {
   const r=await fetch('/browser/v1/pair',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({challenge:value.challenge,hostIncarnation:value.hostIncarnation})});
   const b=await r.json(); if(r.status!==200 || !b.result)throw Error('Pair failed');
   grant=b.result.grant; current=b.result; return {clientId:current.clientId,hostIncarnation:current.hostIncarnation};
  },
  rpc,
  async attach(id) {
   const r=await fetch('/browser/v1/events?subscriptionId='+encodeURIComponent(id),{headers:{'Authorization':'Bearer '+grant}});
   if(r.status!==200)throw Error('SSE attach failed');
   stream={closed:false,bytes:0}; const state=stream;
   (async()=>{const reader=r.body.getReader();try {for(;;){const n=await reader.read();if(n.done)break;state.bytes+=n.value.length;}}catch(e){}finally{state.closed=true;reader.releaseLock();}})();
   return r.status;
  },
  stream(){return stream&&{closed:stream.closed,bytes:stream.bytes};},
  storageEmpty(){return localStorage.length===0&&sessionStorage.length===0&&document.cookie==='';}
 };
})();'''


def require(condition: bool, code: str) -> None:
    if not condition:
        raise RuntimeError(code)


def sha(path: Path) -> str:
    return hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()


class Stub:
    def __init__(self, workspace: Path):
        self.calls = 0
        self.mode = 'approval'
        self.entered = threading.Event()
        self.release = threading.Event()
        owner = self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass
            def do_POST(self):
                # Consume synthetic input without retaining prompts or Provider payloads.
                self.rfile.read(int(self.headers['Content-Length']))
                owner.calls += 1
                if owner.mode == 'hold':
                    owner.entered.set()
                    require(owner.release.wait(45), 'STUB_HOLD_TIMEOUT')
                message = {'role': 'assistant', 'content': 'external-safe-completion'}
                finish = 'stop'
                if owner.calls == 1:
                    message = {'role': 'assistant', 'content': None, 'tool_calls': [{'id': 'external-file-call', 'type': 'function', 'function': {'name': 'file_create', 'arguments': json.dumps({'path': str(workspace / 'approved.txt'), 'content': 'external-approved-content'})}}]}
                    finish = 'tool_calls'
                body = json.dumps({'id': 'stub', 'object': 'chat.completion', 'created': 0, 'model': 'deepseek-flash', 'choices': [{'index': 0, 'message': message, 'finish_reason': finish}], 'usage': {'prompt_tokens': 1, 'completion_tokens': 1, 'total_tokens': 2}}).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def close(self):
        self.release.set()
        self.server.shutdown()
        self.server.server_close()


class Owner:
    def __init__(self, classpath: str, root: Path, config: Path, port: int, lifetime: int):
        environment = dict(os.environ, HFA_FIXTURE_KEY='hfa-external-stub-NOT-REAL-0000', HAIFA_ALLOW_INSECURE_LOOPBACK_MODEL='true')
        # Never write stdout to a file: this pipe carries one-time owner challenges.
        self.process = subprocess.Popen(['java', '-cp', classpath, 'example.HostExample', str(root/'workspace'), str(root/'data'), str(config), str(root/'assets'), str(port), str(lifetime), '--owner-ipc'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, env=environment)
        self.replies = queue.Queue()
        def receive():
            for line in self.process.stdout:
                try:
                    value = json.loads(line)
                    if isinstance(value, dict) and 'event' in value:
                        self.replies.put(value)
                except ValueError:
                    pass
        self.reader = threading.Thread(target=receive, daemon=True)
        self.reader.start()
        ready = self.reply('ready')
        self.endpoint = ready['endpoint']
        self.project = ready['projectId']

    def reply(self, event: str):
        try:
            value = self.replies.get(timeout=30)
        except queue.Empty:
            raise RuntimeError('OWNER_CONTROL_TIMEOUT') from None
        require(value.get('event') == event, 'OWNER_CONTROL_EVENT')
        return value

    def command(self, line: str, event: str):
        self.process.stdin.write(line+'\n')
        self.process.stdin.flush()
        return self.reply(event)

    def close(self):
        if self.process.poll() is None:
            self.process.stdin.write('close\n')
            self.process.stdin.flush()
            try:
                self.process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self.process.terminate()
                self.process.wait(timeout=5)
        require(self.process.returncode == 0, 'OWNER_CLOSE_FAILED')


def build_example(kind: str, root: Path, repository: Path, version: str):
    example = root/kind
    shutil.copytree(ROOT/'examples'/'coding-product-sdk'/kind, example)
    with (root/(kind+'-maven.log')).open('w') as log:
        result = subprocess.run(['mvn', '-B', '-ntp', '-Dmaven.repo.local='+str(repository), '-Dcoding.version='+version, 'package', 'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath', '-Dmdep.outputFile=classpath.txt', '-DincludeScope=runtime'], cwd=example, stdout=log, stderr=subprocess.STDOUT, timeout=300)
    require(result.returncode == 0, 'EXTERNAL_'+kind.upper()+'_BUILD')
    dependencies = (example/'classpath.txt').read_text().strip().split(os.pathsep)
    require(all(Path(p).is_file() and p.endswith('.jar') for p in dependencies), 'EXTERNAL_JAR_CLASSPATH_REQUIRED')
    names = [Path(p).name for p in dependencies]
    require(not any('haifa-agent-cli-' in n or 'coding-terminal' in n for n in names), 'EXTERNAL_UI_CLOSURE')
    if kind == 'client':
        require(all(n.startswith(('haifa-coding-api-', 'haifa-coding-client-', 'jackson-')) for n in names), 'CLIENT_HEAVY_CLOSURE')
    application = example/'target'/('coding-'+kind+'-example-1.0.0.jar')
    require(application.is_file(), 'EXTERNAL_APPLICATION_JAR')
    return os.pathsep.join([str(application), *dependencies]), names


def client(classpath: str, owner: Owner, root: Path, label: str, mode: str, session='', run=''):
    result_file = root/(label+'.json')
    command = ['java', '-cp', classpath, 'example.ClientExample', owner.endpoint, mode, str(result_file)]
    if mode == 'create':
        command.append('--approve-fixture')
    else:
        command.extend([session, run])
    # Client receives no Provider key; stdout/stderr are not a product oracle.
    result = subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=45)
    require(result.returncode == 0 and result_file.is_file(), 'EXTERNAL_CLIENT_'+label.upper())
    return json.loads(result_file.read_text())


def result(reply):
    require(reply['status'] == 200 and 'result' in reply['body'], 'BROWSER_RPC_FAILED')
    return reply['body']['result']


def await_stream_closed(page, timeout):
    # Playwright wait_for_function uses eval inside the page, forbidden by the production CSP.
    # Keep that policy intact and poll a fixed expression through the trusted browser driver.
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if page.evaluate('probe.stream().closed'):
            return
        time.sleep(0.05)
    raise RuntimeError('BROWSER_ESTABLISHED_STREAM_NOT_CLOSED')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-root', type=Path, required=True)
    parser.add_argument('--preview-repository', type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--expected-sha', required=True)
    parser.add_argument('--chromium', required=True)
    args = parser.parse_args()
    root = args.run_root.resolve()
    require(not root.exists() and not root.is_relative_to(ROOT) and not ROOT.is_relative_to(root), 'NEW_EXTERNAL_RUN_ROOT_REQUIRED')
    repository = args.preview_repository.resolve()
    manifest_file = repository/'io/haifa/haifa-coding-host'/args.version/'haifa-coding-product-preview-manifest.json'
    manifest = json.loads(manifest_file.read_text())
    require(manifest['state'] == 'complete' and manifest['sourceSha'] == args.expected_sha and manifest['version'] == args.version, 'PREVIEW_PROVENANCE')
    for artifact in manifest['artifacts']:
        path = repository/artifact['relativePath']
        require(sha(path) == artifact['sha256'] and path.stat().st_size == artifact['size'], 'PREVIEW_HASH')
    root.mkdir(parents=True)
    evidence = {'sourceSha': args.expected_sha, 'previewVersion': args.version, 'manifestSha256': sha(manifest_file), 'provider': 'independent-loopback-stub', 'checks': {}}
    owner = None
    stub = None
    try:
        host_cp, host_names = build_example('host', root, repository, args.version)
        client_cp, client_names = build_example('client', root, repository, args.version)
        evidence['hostRuntimeJars'] = host_names
        evidence['clientRuntimeJars'] = client_names
        evidence['checks']['externalThinJarClosure'] = True
        (root/'workspace').mkdir()
        (root/'assets').mkdir()
        (root/'assets/index.html').write_text('<!doctype html><title>Protocol probe</title><script src="probe.js"></script>', encoding='utf-8')
        (root/'assets/probe.js').write_text(PROBE, encoding='utf-8')
        stub = Stub(root/'workspace')
        config = root/'trusted.yaml'
        config.write_text('''models:
  default: external-fixture
  providers:
    - id: deepseek
      displayName: External Stub
      endpoint: http://127.0.0.1:%d/v1
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
  enabled: [file_stat, file_read, file_create, file_write]
approval:
  mode: ask
  threshold: low
persistence:
  mode: SQLITE
  databasePath: '%s'
  protection: NONE
execution:
  provider: host-guarded
  shell: bash
''' % (stub.server.server_port, str(root/'unused-owner-path.sqlite').replace("'", "''")), encoding='utf-8')
        owner = Owner(host_cp, root, config, 0, 120)
        completed = client(client_cp, owner, root, 'approval', 'create')
        require(completed['state'] == 'COMPLETED' and completed['interactionApplied'] and (root/'workspace/approved.txt').read_text() == 'external-approved-content', 'EXTERNAL_EXACT_APPROVAL')
        evidence['checks']['externalClientSessionExactInteraction'] = True
        from playwright.sync_api import sync_playwright
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(executable_path=args.chromium, headless=True, args=['--no-sandbox'])
            evidence['chromiumVersion'] = browser.version
            context = browser.new_context()
            page = context.new_page()
            page.goto(owner.endpoint+'/browser/')
            require(page.evaluate('typeof probe') == 'object', 'BROWSER_TRUSTED_ASSET')
            evidence['stage'] = 'pair'
            paired = page.evaluate('(proof)=>probe.pair(proof)', owner.command('pair', 'pairing'))
            evidence['stage'] = 'initialize'
            initialized = result(page.evaluate('()=>probe.rpc("initialize",{protocolVersion:"1",clientInfo:{name:"actual-chromium-probe",version:"1"},optionalCapabilities:["coding-product-v1","session-presentation-v1","session-consent-v1"]})'))
            require(initialized['clientId'] == paired['clientId'] and initialized['clientSource'] == 'browser', 'ONE_BOUND_BROWSER_CLIENT')
            stub.mode = 'hold'
            create = {'command': {'kind': 'command', 'operation': 'create', 'clientRequestId': 'browser-held', 'idempotencyKey': 'browser-held-key', 'target': {'projectId': None, 'sessionId': None, 'runId': None, 'interactionId': None, 'targetRevision': None}, 'text': 'synthetic held operation', 'optionId': None, 'executionId': None, 'selections': []}, 'options': {'approved': False, 'includeInContext': False, 'answers': []}}
            evidence['stage'] = 'create'
            created = result(page.evaluate('(request)=>probe.rpc("product/command",request)', create))
            session, run = created['session']['sessionId'], created['receipt']['runId']
            require(stub.entered.wait(15), 'BROWSER_RUN_ACCEPTED')
            evidence['stage'] = 'subscribe'
            subscription = result(page.evaluate('(sessionId)=>probe.rpc("presentation/subscribe",{sessionId})', session))
            require(subscription['snapshot']['scope']['source'] == 'browser' and subscription['snapshot']['consent']['state'] == 'ASK', 'BROWSER_SOURCE_AND_CONSENT')
            subid = subscription['subscriptionId']
            evidence['stage'] = 'attach'
            require(page.evaluate('(id)=>probe.attach(id)', subid) == 200, 'ACTUAL_BROWSER_SSE')
            # Actual native GET without Origin cannot consume browser-owned subscriptions.
            try:
                with urlopen(Request(owner.endpoint+'/v1/events?subscriptionId='+subid, headers={'X-CA-Client-ID': paired['clientId']}), timeout=5) as response:
                    status = response.status
            except __import__('urllib.error', fromlist=['HTTPError']).HTTPError as rejected:
                status = rejected.code
            require(status in (403, 409), 'NATIVE_GET_BROWSER_BYPASS')
            evidence['stage'] = 'revoke'
            owner.command('revoke '+paired['clientId'], 'revoked')
            evidence['stage'] = 'await-revoke-close'
            await_stream_closed(page, 8)
            evidence['stage'] = 'revoked-rpc'
            require(page.evaluate('()=>probe.rpc("client/renew")')['status'] == 403, 'REVOKED_RENEW')
            require(page.evaluate('(request)=>probe.rpc("product/command",request)', create)['status'] == 403, 'REVOKED_COMMAND')
            active = client(client_cp, owner, root, 'accepted-after-revoke', 'inspect', session, run)
            require(active['state'] == 'RUNNING', 'REVOKE_CANCELLED_ACCEPTED_RUN')
            calls = stub.calls
            stub.release.set()
            finished = client(client_cp, owner, root, 'finished-after-revoke', 'await', session, run)
            require(finished['state'] == 'COMPLETED' and stub.calls == calls, 'ACCEPTED_RUN_CONTINUES_WITHOUT_REPLAY')
            evidence['checks']['actualBrowserSseRevokeAcceptedRunSurvives'] = True
            require(page.evaluate('probe.storageEmpty()'), 'PROOF_PERSISTED')
            port = int(owner.endpoint.rsplit(':', 1)[1])
            evidence['stage'] = 'restart'
            owner.close()
            owner = Owner(host_cp, root, config, port, 5)
            evidence['stage'] = 'old-incarnation'
            require(page.evaluate('()=>probe.rpc("client/renew")')['status'] == 403, 'OLD_INCARNATION_ACCEPTED')
            evidence['checks']['sameOriginRestartRejectsOldGrant'] = True
            evidence['stage'] = 'pair-after-restart'
            second = page.evaluate('(proof)=>probe.pair(proof)', owner.command('pair', 'pairing'))
            require(second['hostIncarnation'] != paired['hostIncarnation'], 'INCARNATION_NOT_CHANGED')
            result(page.evaluate('()=>probe.rpc("initialize",{protocolVersion:"1",clientInfo:{name:"actual-chromium-probe",version:"1"},optionalCapabilities:["coding-product-v1","session-presentation-v1","session-consent-v1"]})'))
            evidence['stage'] = 'subscribe-after-restart'
            newsub = result(page.evaluate('(sessionId)=>probe.rpc("presentation/subscribe",{sessionId})', session))
            require(newsub['snapshot']['consent']['state'] == 'ASK', 'CONSENT_RESTORED_ON_RECONNECT')
            page.evaluate('(id)=>probe.attach(id)', newsub['subscriptionId'])
            await_stream_closed(page, 10)
            require(page.evaluate('()=>probe.rpc("client/renew")')['status'] == 403, 'EXPIRED_GRANT_ACCEPTED')
            evidence['checks']['actualBrowserSseAbsoluteExpiry'] = True
            evidence['stage'] = 'reload'
            page.reload()
            require(page.evaluate('probe.storageEmpty()') and page.evaluate('()=>probe.rpc("client/renew")')['status'] == 403, 'RELOAD_RESTORED_PROOF')
            evidence['checks']['pageMemoryOnlyNoSilentRefresh'] = True
            context.close()
            browser.close()
        owner.close()
        owner = None
        evidence['checks']['ownerOrderlyCloseAndDatabaseReopen'] = True
        evidence['providerCalls'] = stub.calls
        evidence['status'] = 'PASS'
    except Exception as failure:
        evidence['status'] = 'FAIL'
        # Do not dump exception text, request arguments, grants or private launcher replies.
        evidence['failureType'] = type(failure).__name__
        for safe_code in ('SSE attach failed', 'Pair failed', 'Failed to fetch', 'Timeout', 'Cannot read properties', 'unsafe-eval', 'Refused to evaluate', 'ReferenceError', 'SyntaxError'):
            if safe_code in str(failure): evidence['probeFailure'] = safe_code
        if isinstance(failure, RuntimeError):
            evidence['failureCode'] = str(failure)
    finally:
        if stub:
            stub.release.set()
        if owner:
            owner.close()
        if stub:
            stub.close()
        (root/'manifest.json').write_text(json.dumps(evidence, indent=2)+'\n', encoding='utf-8')
    print('CODING_SDK_B2='+evidence['status']+' evidence='+str(root/'manifest.json'))
    return 0 if evidence['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
