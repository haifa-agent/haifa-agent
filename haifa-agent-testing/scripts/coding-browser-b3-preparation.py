#!/usr/bin/env python3
"""Independent packed-package/browser/renderer checks. This is NOT real Host acceptance.

Uses a static fixture server plus Playwright-controlled synthetic protocol responses. It never starts
Coding Host, Runtime, a model Provider or a BFF. Actual Host/Windows checks stay gated separately.
"""
from __future__ import annotations
import argparse
import functools
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import threading
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from playwright.sync_api import sync_playwright, expect

ROOT=Path(__file__).resolve().parents[2]
SDK=ROOT/'haifa-agent-applications/haifa-coding-browser-client'
WEB=ROOT/'haifa-agent-applications/haifa-coding-web'
NAME='haifa-agent-coding-client-0.1.5-b3.0.tgz'


def require(condition,code):
    if not condition:raise RuntimeError(code)


def digest(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()


def run_npm(arguments,cwd,log):
    npm=shutil.which('npm.cmd' if os.name=='nt' else 'npm')
    require(npm is not None,'NPM_REQUIRED')
    with log.open('w',encoding='utf-8') as output:
        completed=subprocess.run([npm,*arguments],cwd=cwd,stdout=output,stderr=subprocess.STDOUT,timeout=300)
    require(completed.returncode==0,'EXTERNAL_BUILD_FAILED')


def copy_project(source,target):
    shutil.copytree(source,target,ignore=shutil.ignore_patterns('node_modules','dist','vendor','.git'))


class StaticHandler(SimpleHTTPRequestHandler):
    stopping=threading.Event()
    def do_GET(self):
        if not self.path.startswith("/browser/v1/events?"):return super().do_GET()
        self.send_response(200);self.send_header("Content-Type","text/event-stream; charset=utf-8");self.end_headers()
        try:
            while not self.stopping.wait(0.2):
                self.wfile.write(b": synthetic heartbeat\n\n");self.wfile.flush()
        except ConnectionError:pass

    def log_message(self,*_):pass
    def end_headers(self):
        self.send_header('Content-Security-Policy',"default-src 'self'; script-src 'self'; connect-src 'self'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
        self.send_header('Cache-Control','no-store')
        self.send_header('Referrer-Policy','no-referrer')
        self.send_header('X-Content-Type-Options','nosniff')
        super().end_headers()


def fixture_summary(session):
    return {'sessionId': session, 'projectId': 'project-1', 'title': '合成测试会话 ' + session,
            'status': 'ACTIVE', 'runId': None, 'runStatus': None, 'queuedCount': 0,
            'lastActivityAtEpochMillis': 0, 'revision': 0}


def fixture_snapshot(session,view_id):
    value=json.loads((ROOT/'haifa-agent-applications/haifa-coding-api/contract/fixtures/snapshot.json').read_text(encoding='utf-8'))
    value['scope'].update(source='browser',sessionId=session,viewId=view_id)
    value['session'].update(id=session,title='合成测试会话 '+session,run=None,context='Synthetic renderer fixture; no Host')
    value['entries']=[]
    value['actions']=[{'kind':'submit','target':{'sessionId':session,'runId':None,'interactionId':None,'targetRevision':None},'choice':None,'available':True,'reason':None}]
    if session=='A':
        source=json.loads((ROOT/'haifa-agent-applications/haifa-coding-api/contract/fixtures/snapshot.json').read_text(encoding='utf-8'))
        message=source['entries'][0]
        message.update(
            id='safe-message', role='assistant', status='FINAL', interactionId=None,
            text='## 排查结论\n\n使用 **安全显示**。\n\n'
                 '<img src=x onerror="window.unsafeFixtureExecuted=true"> 中文 😀 \u001b[31m',
        )
        value['entries'].append(message)
        interaction=source['entries'][2]
        interaction['interaction'].update(kind='approval',input={'type':'none','maximumCharacters':0,'minimumSelections':0,'maximumSelections':0,'options':[],'questions':[]},review={'purpose':'仅合成夹具审批展示','contentType':'File content','content':'synthetic review content','environment':[],'technical':[],'risk':None,'targetComplete':True,'contentTruncated':False})
        interaction['interaction']['actions']=[{'kind':'respond','target':{'sessionId':'A','runId':'run-1','interactionId':'interaction-1','targetRevision':7},'choice':'approve','available':True,'reason':None}]
        value['entries'].append(interaction)
        options = [
            {'id': 'first', 'label': 'First fixture option', 'description': None},
            {'id': 'second', 'label': 'Second fixture option', 'description': None},
        ]
        clarification = dict(interaction['interaction'], id='interaction-2', kind='clarification',
                             review=None, actions=[], input={'type': 'single-choice', 'maximumCharacters': 0,
                             'minimumSelections': 1, 'maximumSelections': 1, 'options': options, 'questions': []})
        value['entries'].append(dict(interaction, id='entry-clarification', interaction=clarification))
    return value


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-root',type=Path,required=True)
    parser.add_argument('--expected-sha',required=True)
    parser.add_argument('--chromium',required=True)
    args=parser.parse_args();root=args.run_root.resolve()
    require(not root.exists() and not root.is_relative_to(ROOT) and not ROOT.is_relative_to(root),'NEW_EXTERNAL_RUN_ROOT_REQUIRED')
    head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    require(head==args.expected_sha,'SOURCE_SHA_MISMATCH')
    root.mkdir(parents=True);receipt={'sourceSha':head,'dirty':bool(subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).strip()),'scope':'INDEPENDENT_PREPARATION_ONLY_NO_REAL_HOST','actualHostAcceptance':'BLOCKED_BY_B2_GATE','checks':{}}
    server=None
    try:
        package=SDK/'artifacts'/NAME;require(package.is_file(),'PACK_REQUIRED');receipt['sdkTarballSha256']=digest(package)
        with tarfile.open(package,'r:gz') as archive:
            metadata=json.load(archive.extractfile('package/package.json'))
            require(not metadata.get('dependencies'),'SDK_RUNTIME_DEPENDENCIES')
            sdk_bytes=archive.extractfile('package/dist/index.js').read()
            receipt['sdkEsmSha256']=hashlib.sha256(sdk_bytes).hexdigest()
        receipt['stage']='external-ui-build'
        copy_project(ROOT/'examples/coding-browser-ui',root/'external-ui')
        (root/'external-ui/vendor').mkdir();shutil.copyfile(package,root/'external-ui/vendor'/NAME)
        run_npm(['ci','--no-audit','--no-fund'],root/'external-ui',root/'external-ui-install.log')
        run_npm(['run','build'],root/'external-ui',root/'external-ui-build.log')
        receipt['stage']='official-web-build'
        copy_project(WEB,root/'official-web')
        (root/'haifa-coding-browser-client/artifacts').mkdir(parents=True)
        shutil.copyfile(package,root/'haifa-coding-browser-client/artifacts'/NAME)
        run_npm(['ci','--no-audit','--no-fund'],root/'official-web',root/'official-web-install.log')
        run_npm(['run','build'],root/'official-web',root/'official-web-build.log')
        site=root/'site';shutil.copytree(root/'official-web/dist',site/'browser')
        (site/'sdk').mkdir();(site/'sdk/index.js').write_bytes(sdk_bytes)
        handler=functools.partial(StaticHandler,directory=str(site))
        server=ThreadingHTTPServer(('127.0.0.1',0),handler);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        origin='http://127.0.0.1:'+str(server.server_port)
        network=[];commands=[];subscriptions=0;offline=False;pending_create=[]
        def protocol(route):
            nonlocal subscriptions
            request=route.request;path=request.url.split(origin,1)[-1]
            require(request.url.startswith(origin+'/'),'CROSS_ORIGIN_REQUEST')
            require(not any(x+'=' in request.url.lower() for x in ('grant','challenge','authorization','token')),'PROOF_IN_URL')
            if path.startswith('/browser/v1/events'):
                # Constant synthetic heartbeat source, no Host/domain/Provider implementation.
                route.continue_();return
            data=request.post_data_json if request.method=='POST' else None
            if path.endswith('/info'):result={'hostIncarnation':'host-1','protocolVersion':'1','browserAuthorization':'browser-grant-v1','displayName':'Synthetic browser fixture'};id_=None
            elif path.endswith('/pair'):result={'grant':'synthetic-browser-fixture-NOT-REAL','clientId':'client-1','hostIncarnation':'host-1','expiresAtEpochMillis':__import__('time').time_ns()//1000000+60000};id_=None
            else:
                id_=data['id'];method=data['method'];network.append(method)
                if method=='initialize':result={'protocolVersion':'1','hostIncarnation':'host-1','clientId':'client-1','projectId':'project-1','displayName':'Synthetic browser fixture','clientSource':'browser','browserAuthorization':'browser-grant-v1','optionalCapabilities':['coding-product-v1','session-presentation-v1','session-consent-v1']}
                elif method=='product/query':
                    result={'sessions':[fixture_summary(session) for session in ('A', 'B')],'run':None,'outcome':None,'models':[],'queuedMessages':[],'shellExecutions':[],'workspaces':[],'lines':[],'authentication':None,'truncated':False}
                    if data['params']['operation']=='workspaces':
                        result['workspaces']=[
                            {'id':'extra','safeDisplayName':'另一个文件夹','mode':'DEVELOP','status':'ACTIVE','revocable':True},
                            {'id':'primary','safeDisplayName':'示例项目','mode':'READ','status':'ACTIVE','revocable':False},
                        ]
                elif method=='presentation/subscribe':
                    subscriptions+=1;result={'subscriptionId':'subscription-'+str(subscriptions),'snapshot':fixture_snapshot(data['params']['sessionId'],'view-'+str(subscriptions))}
                elif method=='product/command':
                    command=data['params']['command'];commands.append({'operation':command['operation'],'target':command['target'],'optionId':command['optionId']})
                    result={'receipt':{'clientRequestId':command['clientRequestId'],'disposition':'accepted','operationId':'synthetic-operation','runId':'run-1','reasonCode':None},'session':None,'queuedMessage':None,'restoredMessage':None,'shellPlan':None,'shellExecution':None,'shellResult':None,'compaction':None,'export':None,'modelSelection':None,'interactionReceipt':None,'interaction':None}
                    if command['operation'] == 'create':
                        result['session'] = fixture_summary('A')
                        pending_create.append((route, {'jsonrpc': '2.0', 'id': id_, 'result': result}))
                        return
                elif method=='client/renew':
                    if offline:
                        route.abort('internetdisconnected');return
                    result={'renewed':True}
                else:result={'closed':True}
            route.fulfill(status=200,content_type='application/json',body=json.dumps({'jsonrpc':'2.0','id':id_,'result':result}))
        receipt['stage']='browser-launch'
        with sync_playwright() as playwright:
            browser=playwright.chromium.launch(executable_path=args.chromium,headless=True,args=['--no-sandbox'])
            receipt['chromiumVersion']=browser.version
            context=browser.new_context(viewport={'width':1440,'height':1000});page=context.new_page();errors=[];page.on('pageerror',lambda _:errors.append('PAGE_ERROR'))
            page.goto(origin+'/browser/');expect(page.locator('h1')).to_have_text('从一个想法开始')
            page.screenshot(path=str(root/'unpaired-desktop.png'),full_page=True)
            require(page.evaluate("typeof process==='undefined'&&typeof Buffer==='undefined'"),'NODE_GLOBAL_IN_BROWSER')
            receipt['stage']='browser-pure-protocol'
            fixture=json.loads((ROOT/'haifa-agent-applications/haifa-coding-api/contract/fixtures/snapshot.json').read_text(encoding='utf-8'))
            pure=page.evaluate('''async(value)=>{const sdk=await import('/sdk/index.js');const bytes=new TextEncoder().encode('event: presentation\\r\\ndata: 中文 😀\\r\\n\\r\\n');const stream=new ReadableStream({start(c){for(const b of bytes)c.enqueue(Uint8Array.of(b));c.close();}});const frames=[];for await(const data of sdk.readSse(stream,'presentation',4096,new AbortController().signal))frames.push(data);return{valid:sdk.checkEnvelope(value),frames};}''',fixture)
            require(pure=={'valid':True,'frames':['中文 😀']},'BROWSER_PURE_PROTOCOL')
            receipt['checks']['packedSdkExecutesWithoutNodeOrEvalUnderCsp']=True
            receipt['stage']='synthetic-pairing'
            page.route('**/browser/v1/**',protocol)
            page.get_by_label('一次性配对码',exact=True).fill('synthetic-challenge-NOT-REAL');page.get_by_role('button',name='连接工作区',exact=False).click()
            expect(page.locator('#pairing')).to_be_hidden();expect(page.locator('#pair-code')).to_have_value('')
            expect(page.locator('#context .workspace-name')).to_have_text('示例项目')
            expect(page.locator('#context')).to_contain_text('只读')
            receipt['stage']='synthetic-session-open'
            page.get_by_role('button',name='合成测试会话 A',exact=False).click();expect(page.locator('#session-title')).to_have_text('合成测试会话 A')
            expect(page.locator('#context')).not_to_contain_text('Synthetic renderer fixture')
            expect(page.locator('#transcript .reply-markdown h2')).to_have_text('排查结论')
            expect(page.locator('#transcript .reply-markdown strong')).to_have_text('安全显示')
            expect(page.locator('#transcript img')).to_have_count(0);require(page.evaluate('window.unsafeFixtureExecuted===undefined'),'DISPLAY_TEXT_EXECUTED')
            receipt['stage']='synthetic-approval'
            approve=page.get_by_role('button',name='批准执行',exact=True);expect(approve).to_be_disabled()
            page.get_by_label('我已核对本次工具操作的内容和作用范围').check();expect(approve).to_be_enabled();approve.click()
            expect(page.locator('#notice')).to_contain_text('已接收操作')
            require(any(c['operation']=='respond' and c['optionId']=='approve' and c['target']['sessionId']=='A' and c['target']['runId']=='run-1' and c['target']['interactionId']=='interaction-1' and c['target']['targetRevision']==7 for c in commands),'EXACT_SYNTHETIC_ACTION')
            receipt['stage']='synthetic-draft-switch'
            first_option = page.get_by_role('radio', name='First fixture option')
            second_option = page.get_by_role('radio', name='Second fixture option')
            second_option.check()
            first_option.check()
            draft=page.locator('#draft');draft.fill('中文会话 A 草稿');page.get_by_role('button',name='合成测试会话 B',exact=False).click();expect(page.locator('#session-title')).to_have_text('合成测试会话 B');expect(draft).to_have_value('');draft.fill('会话 B 草稿');page.get_by_role('button',name='合成测试会话 A',exact=False).click();expect(draft).to_have_value('中文会话 A 草稿')
            expect(first_option).to_be_checked()
            expect(second_option).not_to_be_checked()
            receipt['checks']['lastRadioSelectionSurvivesRerender'] = True
            require(page.evaluate("localStorage.length===0&&sessionStorage.length===0&&document.cookie==='';"),'BROWSER_STORAGE_USED')
            require(not errors,'PAGE_RUNTIME_ERROR');receipt['checks']['syntheticRendererExactActionSafeTextAndDraftIsolation']=True
            receipt['stage']='synthetic-network-restored'
            previous_subscriptions=subscriptions
            offline=True
            page.evaluate("document.dispatchEvent(new Event('visibilitychange'))")
            expect(page.locator('#connection-label')).to_have_text('连接暂时中断')
            offline=False
            page.evaluate("window.dispatchEvent(new Event('online'))")
            expect(page.locator('#session-subtitle')).to_have_text('基于当前工作区的会话与任务')
            require(subscriptions>previous_subscriptions,'VISIBLE_VIEW_NOT_RESTORED')
            receipt['checks']['visibleViewRecoversWhenNetworkReturns']=True
            receipt['stage'] = 'synthetic-late-create'
            page.locator('#new-session').click()
            draft.fill('Older fixture request')
            page.locator('#send').click()
            page.get_by_role('button', name='合成测试会话 A', exact=False).click()
            expect(page.locator('#session-title')).to_have_text('合成测试会话 A')
            page.locator('#new-session').click()
            draft.fill('Newer compose draft')
            require(len(pending_create) == 1, 'CREATE_FIXTURE_REQUEST_NOT_HELD')
            create_route, create_reply = pending_create.pop()
            create_route.fulfill(status=200, content_type='application/json', body=json.dumps(create_reply))
            expect(page.locator('#send')).to_be_enabled()
            expect(page.locator('#session-title')).to_have_text('从一个想法开始')
            expect(draft).to_have_value('Newer compose draft')
            receipt['checks']['lateCreateDoesNotNavigateAcrossSelectionGenerations'] = True
            page.unroute('**/browser/v1/**');context.close()
            receipt['stage']='narrow-layout'
            mobile=browser.new_context(viewport={'width':390,'height':844});page=mobile.new_page();page.goto(origin+'/browser/');expect(page.locator('#menu')).to_be_visible()
            require(page.evaluate('document.documentElement.scrollWidth<=innerWidth'),'MOBILE_HORIZONTAL_OVERFLOW')
            require(page.locator('#sidebar').evaluate('(node)=>node.inert'),'HIDDEN_DRAWER_FOCUSABLE')
            page.get_by_role('button',name='展开会话列表').click();require(not page.locator('#sidebar').evaluate('(node)=>node.inert'),'DRAWER_NOT_ACCESSIBLE');page.keyboard.press('Escape');require(page.locator('#sidebar').evaluate('(node)=>node.inert'),'DRAWER_ESCAPE_FAILED')
            page.screenshot(path=str(root/'unpaired-mobile.png'),full_page=True);mobile.close();browser.close()
            receipt['checks']['responsiveStaticUiKeyboardAndNarrowLayout']=True
        receipt['checks']['externalUiAndOfficialWebConsumeTarballOutsideRepository']=True
        receipt['protocolCalls']=network;receipt['syntheticCommandCount']=len(commands);receipt['actualProviderCalls']=0;receipt['status']='PASS'
    except Exception as failure:
        receipt['status']='FAIL';receipt['failureType']=type(failure).__name__
        if isinstance(failure,RuntimeError):receipt['failureCode']=str(failure)
    finally:
        if server:StaticHandler.stopping.set();server.shutdown();server.server_close()
        (root/'manifest.json').write_text(json.dumps(receipt,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    print('B3_PREPARATION='+receipt['status']+' evidence='+str(root/'manifest.json'))
    return 0 if receipt['status']=='PASS' else 1


if __name__=='__main__':raise SystemExit(main())
