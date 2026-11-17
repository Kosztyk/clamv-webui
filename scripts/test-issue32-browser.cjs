/* Minimal DOM harness verifying the real REST polling script; no npm dependencies. */
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');
const code = fs.readFileSync(path.join(__dirname, '../src/main/resources/static/js/rest-api.js'), 'utf8');
async function runScenario(status) {
  let now = 1000, requests = 0;
  const timers = [], listeners = {};
  const stub = () => ({textContent:'', classList:{add(){}}, replaceChildren(){},append(){},addEventListener(){},querySelector(){return null;}, querySelectorAll(){return [];}});
  const msg=stub(), table=stub(), page=stub();
  const root = {
    isConnected:true,dataset:{pageSize:'25'},addEventListener(){},querySelectorAll(){return [];},
    querySelector(name){return name==='[data-rest-jobs-message]'?msg:name==='[data-rest-job-table]'?table:name==='[data-rest-page]'?page:null;}
  };
  const doc = {
    visibilityState:'visible',
    querySelectorAll(name){return name==='[data-rest-jobs]'?[root]:[];},
    createElement(){return stub();},
    querySelector(){return null;},
    addEventListener(name,fn){(listeners[name]??=[]).push(fn);}
  };
  class Clock extends Date {static now(){return now;}}
  const context={document:doc, Date:Clock, URLSearchParams, setInterval: fn=>{timers.push(fn);return timers.length;}, clearInterval(){},
    fetch:async()=>{requests++;return {ok:true,json:async()=>({jobs:[{id:'sample',status}],total:1})};}};
  vm.runInNewContext(code,context);
  (listeners.DOMContentLoaded||[]).forEach(f=>f());
  async function flush(){for(let i=0;i<6;i++)await Promise.resolve();}
  await flush();assert.equal(requests,1,'initial load');
  doc.visibilityState='hidden';now+=60000;timers.forEach(f=>f());await flush();
  assert.equal(requests,1,'hidden tab must not poll');
  doc.visibilityState='visible';(listeners.visibilitychange||[]).forEach(f=>f());await flush();
  assert.equal(requests,2,'visible tab refreshes immediately');
  now+=5000;timers.forEach(f=>f());await flush();
  assert.equal(requests,status==='running'?3:2,'active=5s, idle=30s');
  now+=25000;timers.forEach(f=>f());await flush();
  assert.equal(requests,status==='running'?4:3,'idle refreshes after 30s');
}
(async()=>{await runScenario('completed');await runScenario('running');console.log('PASS: hidden tab, resume, idle and active polling');})().catch(e=>{console.error(e);process.exitCode=1;});
