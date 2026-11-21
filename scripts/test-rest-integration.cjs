/* End-to-end check: real GUI + real REST job implementation, simulated ClamAV engine.
 * Build GUI with mvn package. Set REST_API_SOURCE to the job-enabled REST source,
 * install its npm dependencies, and install jsdom 26 in a temporary tool directory.
 * Run with NODE_PATH=<tool-directory>/node_modules node scripts/test-rest-integration.cjs.
 */
const fs=require('fs'),os=require('os'),path=require('path'),assert=require('assert/strict');
const {spawn}=require('child_process');
const {JSDOM,CookieJar}=require('jsdom');
const root=path.resolve(__dirname,'..');
const apiSource=process.env.REST_API_SOURCE;
if(!apiSource)throw new Error('Set REST_API_SOURCE to the custom REST API source directory');
const temp=fs.mkdtempSync(path.join(os.tmpdir(),'clamav-gui-e2e-'));
const guiPort=18081,apiPort=13001;
const base='http://127.0.0.1:'+guiPort;
const key='integration-test-key';
const children=[];let dom;const checks=[];
function check(name, value=true){assert.ok(value,name);checks.push(name);console.log('PASS '+name);}
function child(name,cmd,args,env){const fd=fs.openSync(path.join(temp,name+'.log'),'w');const p=spawn(cmd,args,{cwd:temp,env:{...process.env,...env},stdio:['ignore',fd,fd]});children.push(p);return p;}
async function wait(fn,message,timeout=30000){const end=Date.now()+timeout;let last;while(Date.now()<end){try{const value=await fn();if(value)return value;}catch(e){last=e;}await new Promise(r=>setTimeout(r,100));}throw new Error(message+(last?': '+last.message:''));}
(async()=>{
  child('api',process.execPath,['-e',`
    const {createRequire}=require('module'),fs=require('fs/promises');
    const req=createRequire(process.env.REST_API_SOURCE+'/package.json');
    const clam=req.resolve('clamscan');
    require.cache[clam]={id:clam,filename:clam,loaded:true,exports:class {async init(){return {getVersion:async()=> 'ClamAV test/1/Mon Sep 28 2026',scanFile:async p=>{const b=await fs.readFile(p);await new Promise(r=>setTimeout(r,150));if(b.includes('SIMULATE-FAIL'))throw new Error('Simulated scan failure');return {isInfected:b.includes('SIMULATE-INFECTED'),viruses:b.includes('SIMULATE-INFECTED')?['Test.Signature']:[]};}};}}};
    req('./src/server').makeServer(req('./src/config')).then(s=>{process.on('SIGTERM',async()=>{s.close();await s.jobs.stop();process.exit();});});
  `],{REST_API_SOURCE:apiSource,APP_HOST:'127.0.0.1',APP_PORT:String(apiPort),APP_API_KEY:key,APP_FORM_KEY:'FILES',APP_DATA_DIR:path.join(temp,'api-state'),APP_TEMP_FILE_DIR:path.join(temp,'api-uploads'),APP_MAX_CONCURRENT_SCANS:'1',NODE_ENV:'test'});
  child('gui','java',['-jar',path.join(root,'target/clamav-web-client-2.3.9-kosz.2.jar'),'--server.address=127.0.0.1','--server.port='+guiPort,'--spring.datasource.url=jdbc:h2:mem:e2e;MODE=PostgreSQL;DB_CLOSE_DELAY=-1'],{CLAMAV_REST_URL:'http://127.0.0.1:'+apiPort,CLAMAV_REST_API_KEY:key,CLAMAV_REST_TIMEOUT_SECONDS:'3'});
  await wait(async()=>{const r=await fetch(base+'/login');return r.ok;},'GUI startup');
  await wait(async()=>{const r=await fetch('http://127.0.0.1:'+apiPort+'/api/v1/stats',{headers:{'X-API-Key':key}});return r.ok;},'API startup');
  const login=await fetch(base+'/login'),loginHtml=await login.text();
  let cookie=login.headers.getSetCookie().map(s=>s.split(';')[0]).join('; ');
  const csrf=loginHtml.match(/name="_csrf"[^>]*value="([^"]+)"/)?.[1];assert.ok(csrf,'login CSRF token');
  const signed=await fetch(base+'/login',{method:'POST',redirect:'manual',headers:{Cookie:cookie,'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({username:'admin',password:'admin',_csrf:csrf})});
  if(signed.headers.getSetCookie().length)cookie=signed.headers.getSetCookie().map(s=>s.split(';')[0]).join('; ');
  check('Authenticated GUI login',signed.status===302&&signed.headers.get('location').endsWith('/dashboard'));
  const page=await fetch(base+'/api-reports',{headers:{Cookie:cookie}}),html=await page.text();check('Reports page rendered',page.ok&&html.includes('data-rest-reports'));
  check('API key absent from browser HTML',!html.includes(key));
  const uiCsrf=html.match(/name="_csrf" content="([^"]+)"/)?.[1];assert.ok(uiCsrf,'page CSRF token');
  const form=new FormData();form.append('files',new Blob(['clean file']),'normal.txt');form.append('files',new Blob(['SIMULATE-INFECTED']),'=malicious.txt');
  const submitted=await fetch(base+'/rest-monitor/scan',{method:'POST',headers:{Cookie:cookie,'X-CSRF-TOKEN':uiCsrf},body:form});
  const accepted=await submitted.json();check('GUI streams multipart to actual asyncscan endpoint',submitted.status===202&&accepted.job_id);
  const id=accepted.job_id;
  const complete=await wait(async()=>{const r=await fetch(base+'/rest-monitor/jobs/'+id,{headers:{Cookie:cookie}});const j=await r.json();return j.status==='completed'&&j;},'API job completion');
  check('Per-file detection and completed progress',complete.verdict==='infected'&&complete.progress.completed_files===2&&complete.files[1].result.viruses[0]==='Test.Signature');
  const report=await (await fetch(base+'/rest-monitor/report',{headers:{Cookie:cookie}})).json();
  check('Report aggregates real API history',report.included_jobs===1&&report.infected_files===1&&report.files_scanned===2&&!report.truncated);
  const csv=await fetch(base+'/rest-monitor/report.csv',{headers:{Cookie:cookie}});const csvText=await csv.text();check('CSV exports detections and neutralizes formulas',csv.ok&&csvText.includes('Test.Signature')&&csvText.includes("'=malicious.txt"));
  const denied=await fetch(base+'/rest-monitor/jobs/'+id+'/webhook/retry',{method:'POST',headers:{Cookie:cookie}});check('Mutations without CSRF rejected',denied.status===403);
  const jar=new CookieJar();for(const c of cookie.split('; '))jar.setCookieSync(c,base);
  const errors=[];
  dom=new JSDOM(html,{url:base+'/api-reports',runScripts:'outside-only',pretendToBeVisual:true,cookieJar:jar});
  const w=dom.window;
  w.fetch=(url,opts={})=>fetch(new URL(url,base),{...opts,headers:{...opts.headers,Cookie:cookie}});
  w.AbortController=AbortController;
  w.addEventListener('error',e=>errors.push(e.message));
  await new Promise(r=>w.document.addEventListener('DOMContentLoaded',r,{once:true}));
  w.eval(fs.readFileSync(path.join(root,'src/main/resources/static/js/rest-api.js'),'utf8'));
  w.document.dispatchEvent(new w.Event('DOMContentLoaded'));
  await wait(()=>w.document.querySelector('[data-rest-job-table]').textContent.includes(id),'DOM job list');
  check('Live UI renders API totals and jobs',w.document.querySelector('[data-rest-metrics]').textContent.includes('Files scanned'));
  w.document.querySelector('[data-rest-job]').click();
  await wait(()=>w.document.querySelector('[data-rest-detail]').textContent.includes('Test.Signature'),'DOM job detail');check('Job detail interaction renders file verdicts');
  w.document.querySelector('[data-rest-analyze]').click();
  await wait(()=>w.document.querySelector('[data-rest-analytics]').textContent.includes('Test.Signature'),'DOM analytics');check('Analytics interaction renders daily totals and signatures');
  const filters=w.document.querySelector('[data-rest-filters]');filters.elements.namedItem('infected').value='false';filters.dispatchEvent(new w.Event('submit',{bubbles:true,cancelable:true}));
  await wait(()=>w.document.querySelector('[data-rest-job-table]').textContent.includes('No matching API jobs'),'DOM filter');check('UI filters reach real API');
  const before=w.document.querySelector('[data-rest-status]').textContent;
  w.document.dispatchEvent(new w.Event('app:page-changed'));
  await wait(()=>w.document.querySelector('[data-rest-job-table]').textContent.includes(id),'PJAX reinitialization');check('Live polling reinitializes after tab navigation');
  check('No frontend JavaScript errors',errors.length===0);
  const auditHtml=await (await fetch(base+'/admin/audit',{headers:{Cookie:cookie}})).text();check('GUI audit includes API submission and export',auditHtml.includes('REST_SCAN_SUBMIT')&&auditHtml.includes('REST_REPORT_EXPORT'));
  console.log(JSON.stringify({checks:checks.length,temp},null,2));
})().catch(e=>{console.error(e);console.error('Logs:',temp);process.exitCode=1;}).finally(()=>{dom?.window.close();children.forEach(p=>p.kill('SIGTERM'));});
