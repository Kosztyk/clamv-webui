/* External REST API UI. Authenticated same-origin proxy only; no external API keys in the browser. */
(() => {
  const initialized = new WeakSet();
  const text = (el, value) => { if (el) el.textContent = value == null || value === '' ? '—' : String(value); };
  const element = (tag, value, className) => {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (value !== undefined) text(node, value);
    return node;
  };
  function csrf() {
    const name = document.querySelector('meta[name="_csrf_header"]')?.content;
    const token = document.querySelector('meta[name="_csrf"]')?.content;
    return name && token ? {[name]:token} : {};
  }
  async function request(path, opts={}) {
    const response = await fetch(path, {
      credentials:'same-origin', cache:'no-store', ...opts,
      headers:{'Accept':'application/json',...(opts.headers||{})}
    });
    let data;
    try {data = await response.json();} catch {throw new Error('Invalid REST response (HTTP '+response.status+')');}
    if (!response.ok) throw new Error(data.message || data.error || data.detail || 'HTTP '+response.status);
    if (data?.success === false) throw new Error(data.message || data.error || 'External API rejected the request');
    return data;
  }
  function unwrap(value) {
    for (let i=0;i<6 && value && typeof value==='object' && !Array.isArray(value) && value.data!==undefined;i++) value=value.data;
    return value;
  }
  function first(object,...keys) {
    for(const key of keys) if(object && object[key] !== undefined && object[key] !== null) return object[key];
    return '';
  }
  function records(raw) {
    let value=raw;
    for(let depth=0;depth<6;depth++) {
      if(Array.isArray(value)) return value;
      if(!value || typeof value!=='object') break;
      const next=['jobs','history','items','records','results','entries','scans','data'].map(k=>value[k]).find(v=>v && typeof v==='object');
      if (!next) break;
      value=next;
    }
    return null;
  }
  function deepStat(object,names,depth=0) {
    if (!object || typeof object!=='object' || depth>5) return undefined;
    for(const [k,v] of Object.entries(object)) {
      if(names.some(n=>k.toLowerCase().replace(/[^a-z0-9]/g,'')===n.toLowerCase().replace(/[^a-z0-9]/g,'')) && (typeof v==='number'||typeof v==='string')) return v;
    }
    for(const [k,v] of Object.entries(object)) {
      if(v && typeof v==='object' && !['files','jobs','results','history'].includes(k)) {
        const found=deepStat(v,names,depth+1);if(found!==undefined)return found;
      }
    }
    return undefined;
  }
  function humanBytes(value){if(value === undefined || value === '')return value;const n=Number(value);if(!Number.isFinite(n))return value;const units=['B','KiB','MiB','GiB','TiB'];let size=n,index=0;while(Math.abs(size)>=1024&&index<units.length-1){size/=1024;index++;}return (index?size.toFixed(1):String(size))+' '+units[index];}
  function metric(root,name,value) {
    const card=element('div',undefined,'metric');card.append(element('span',name),element('strong',value===undefined?'—':value));root.append(card);
  }
  function renderSummary(root,data) {
    const state=root.querySelector('[data-rest-status]');
    const container=root.querySelector('[data-rest-metrics]');
    container?.replaceChildren();
    if (data.configured===false) {text(state,data.message||'External API is not configured');return;}
    const stats=unwrap(data.stats||data);text(state,'Connected · external REST API');
    if(!container)return;
    metric(container,'Files scanned',deepStat(stats,['files_scanned','total_files_scanned','filesScanned','scanned_files']));
    metric(container,'Detections',deepStat(stats,['infected_files','total_infected_files','files_infected','infectedFiles','detections']));
    metric(container,'Failed jobs',deepStat(stats,['failed_jobs','jobs_failed','total_failed_jobs']));
    metric(container,'Bytes scanned',humanBytes(deepStat(stats,['bytes_scanned','total_bytes_scanned','bytesScanned'])));
    const queue=stats?.queue||stats?.queues||{};
    metric(container,'Queued',(first(queue,'queued','pending','waiting') !== '' ? first(queue,'queued','pending','waiting') : deepStat(stats,['queued_jobs','jobs_queued'])));
    metric(container,'Running',(first(queue,'running','active') !== '' ? first(queue,'running','active') : deepStat(stats,['running_jobs','jobs_running'])));
    text(root.querySelector('[data-rest-retention]'),stats?.retention?.description||'Statistics from the external REST API');
    text(root.querySelector('[data-rest-raw]'),JSON.stringify(stats,null,2));
  }
  function bindSummaries() {document.querySelectorAll('[data-rest-summary]').forEach(root=>{
    if(initialized.has(root))return;initialized.add(root);
    async function reload(){try{renderSummary(root,await request('/rest-monitor/summary'));}catch(e){text(root.querySelector('[data-rest-status]'),e.message);}}
    reload();const timer=setInterval(()=>{if(!root.isConnected){clearInterval(timer);return;}reload();},10000);
    root.addEventListener('clamav:rest-refresh',reload);
  });}
  function queryFor(form,page,size) {
    const query=new URLSearchParams();
    if(form) new FormData(form).forEach((v,k)=>{
      if(!v || (k==='view'))return;
      if((k==='from'||k==='to') && String(v).includes('T')) {
        const date=new Date(v);if(!isNaN(date.getTime()))v=date.toISOString();
      }
      query.set(k,String(v));
    });
    query.set('page',String(page));query.set('page_size',String(size));return query;
  }
  function status(job){return String(first(job,'status','state')||'unknown').toLowerCase();}
  function jobId(job){return String(first(job,'id','job_id','jobId')||'');}
  function infected(job){return job.infected===true||Number(first(job,'infected_count','infectedCount'))>0||/infect|virus|found/i.test(String(first(job,'verdict','result')||''));}
  function finished(job){return /complet|finish|fail|error|cancel|done|success/i.test(status(job));}
  function formatTime(value){if(!value)return '—';const date=new Date(value);return isNaN(date.getTime())?String(value):date.toLocaleString(document.documentElement.lang || 'en');}
  function renderFiles(target, raw) {
    const data=unwrap(raw);const source=data?.job||data;
    let files=first(source,'files','file_results','fileResults','results','scan_results');
    files=records(files)|| (Array.isArray(files)?files:[]);
    if(!files.length) return false;
    const title=element('h4','Files and scan results');target.append(title);
    const table=element('table',undefined,'rest-jobs-table');
    const head=element('tr');['File','Verdict','Detection','Error'].forEach(name=>head.append(element('th',name)));
    const thead=element('thead');thead.append(head);table.append(thead);
    const body=element('tbody');
    files.forEach(file=>{
      const row=element('tr');
      [first(file,'filename','file_name','name','path'),first(file,'verdict','status','result'),
       first(file,'virus','virus_name','signature','detection','detected'), first(file,'error','message')]
       .forEach(value=>row.append(element('td',value)));
      body.append(row);
    });table.append(body);target.append(table);return true;
  }
  function showJob(target, raw) {
    const job=unwrap(raw);const value=job?.job||job;
    target.replaceChildren();target.hidden=false;
    const header=element('div',undefined,'rest-detail-header');
    header.append(element('h3','Job '+(jobId(value)||'details')));
    const pill=element('span',status(value), 'rest-job-status');header.append(pill);target.append(header);
    const description=element('p',`Status: ${status(value)} · Verdict: ${first(value,'verdict','result')||'pending'} · Completed files: ${first(value,'completed_files','files_completed','completedFiles')||'—'}`);
    target.append(description);
    const files=renderFiles(target,value);
    if(!files) {const rawBox=element('details');rawBox.append(element('summary','Full job details'),element('pre',JSON.stringify(value,null,2)));target.append(rawBox);}
    return value;
  }
  function bindJobs() {document.querySelectorAll('[data-rest-jobs]').forEach(root=>{
    if(initialized.has(root))return;initialized.add(root);
    let page=1, total=0;const limit=Number(root.dataset.pageSize)||25;
    const form=root.querySelector('[data-rest-filters]');
    const msg=root.querySelector('[data-rest-jobs-message]');const table=root.querySelector('[data-rest-job-table]');
    const pageLabel=root.querySelector('[data-rest-page]');const detail=root.querySelector('[data-rest-detail]');
    let refreshing=false;
    const query=()=>queryFor(form,page,limit);
    async function detailFor(id,preview){
      if(!detail)return;
      try {
        const value=await request('/rest-monitor/jobs/'+encodeURIComponent(id));showJob(detail,value);
        const actions=element('div',undefined,'rest-actions');
        for(const [label,suffix] of [['Progress','progress'],['Webhook','webhook']]) {
          const btn=element('button',label,'btn btn-outline-light btn-sm');btn.type='button';btn.addEventListener('click',async()=>{
            try{const data=await request('/rest-monitor/jobs/'+encodeURIComponent(id)+'/'+suffix);const pre=element('pre',JSON.stringify(data,null,2));detail.append(pre);}
            catch(e){detail.append(element('p',e.message,'rest-error'));}
          });actions.append(btn);
        }
        const retry=element('button','Retry webhook','btn btn-outline-light btn-sm');retry.type='button';
        retry.addEventListener('click',async()=>{
          try{await request('/rest-monitor/jobs/'+encodeURIComponent(id)+'/webhook/retry',{method:'POST',headers:csrf()});detail.append(element('p','Webhook retry accepted'));}
          catch(e){detail.append(element('p',e.message,'rest-error'));}
        });actions.append(retry);detail.append(actions);
      }catch(e){showJob(detail,preview||{id});detail.append(element('p','Unable to load details: '+e.message,'rest-error'));}
    }
    async function load(silent=false){if(refreshing)return;refreshing=true;if(!silent)text(msg,'Loading API jobs…');
      try{
        const route=form?.querySelector('[name=view]')?.value==='history'?'/rest-monitor/history':'/rest-monitor/jobs';
        const data=await request(route+'?'+query());const list=records(data);
        if(!list) throw new Error('REST API returned an unrecognized history format; no data was discarded');
        total=Number(data.total ?? list.length);table?.replaceChildren();
        if(!list.length) text(msg,'No matching API jobs');else text(msg,`Showing ${(page-1)*limit+1}–${(page-1)*limit+list.length} of ${total} jobs`);
        text(pageLabel,`Page ${page} · ${total} jobs`);
        if(!table)return;
        const scroll=element('div',undefined,'rest-table-scroll');const t=element('table',undefined,'rest-jobs-table');
        const thead=element('thead'),heads=element('tr');
        ['Job ID','Created','Status','Verdict','Files','Request ID'].forEach(h=>heads.append(element('th',h)));thead.append(heads);t.append(thead);
        const tbody=element('tbody');
        list.forEach(job=>{
          const row=element('tr');const id=jobId(job);const cell=element('td');
          const btn=element('button',id||'Details','rest-job-link');btn.type='button';
          btn.addEventListener('click',()=>detailFor(id,job));cell.append(btn);row.append(cell);
          const progress=first(job,'files_completed','completed_files','completedFiles');
          const all=first(job,'files_total','total_files','totalFiles');
          [formatTime(first(job,'created_at','createdAt','created','submittedAt')),status(job),
            first(job,'verdict','result')||(infected(job)?'infected':'—'),
            all!==''?`${progress||0}/${all}`:progress,first(job,'request_id','requestId')]
          .forEach(v=>row.append(element('td',v)));
          tbody.append(row);
        });t.append(tbody);scroll.append(t);table.append(scroll);
      }catch(e){text(msg,e.message);msg?.classList.add('rest-error');}
      finally{refreshing=false;}
    }
    root.addEventListener('clamav:rest-refresh',()=>load(true));
    form?.addEventListener('submit',e=>{e.preventDefault();page=1;load();});
    root.querySelector('[data-rest-prev]')?.addEventListener('click',()=>{if(page>1){page--;load();}});
    root.querySelector('[data-rest-next]')?.addEventListener('click',()=>{if(page*limit<total){page++;load();}});
    root.querySelector('[data-rest-analyze]')?.addEventListener('click',async()=>{
      const result=root.querySelector('[data-rest-analytics]');const statusEl=root.querySelector('[data-rest-report-status]');
      text(statusEl,'Generating analytics…');result?.replaceChildren();
      try{const report=await request('/rest-monitor/analytics?'+query());
        text(statusEl,'Analyzed '+report.inspectedJobs+' jobs · '+(report.scope||''));
        for(const [label,key] of [['Clean','cleanJobs'],['Infected','infectedJobs'],['Failed','failedJobs'],['Pending','pendingJobs']])metric(result,label,report[key]);
      }catch(e){text(statusEl,e.message);}
    });
    root.querySelectorAll('[data-rest-export]').forEach(btn=>btn.addEventListener('click',()=>{
      const qp=query();qp.set('format',btn.dataset.restExport);
      window.location.assign('/rest-monitor/export?'+qp.toString());
    }));
    load();const timer=setInterval(()=>{if(!root.isConnected){clearInterval(timer);return;}load(true);},5000);
  });}
  function bindUploads() {document.querySelectorAll('form[data-rest-upload]').forEach(async form=>{
    if(initialized.has(form))return;initialized.add(form);
    const state=form.parentElement?.querySelector('[data-rest-upload-status]');const hooks=form.querySelector('[data-rest-webhooks]');
    const outcome=element('div',undefined,'rest-upload-outcome');state?.after(outcome);
    try {const hooksData=await request('/rest-monitor/webhooks');
      (records(hooksData)||[]).forEach(item=>{const id=first(item,'id','webhook_id','webhookId');if(!id)return;
        const option=element('option',first(item,'name','label')||id);option.value=id;hooks?.append(option);
      });}catch{ /* Webhook discovery is optional for some REST installations. */ }
    let poll=null;
    form.addEventListener('submit',async event=>{
      event.preventDefault();const files=form.querySelector('input[type="file"]');if(!files?.files.length){text(state,'Choose a file first');return;}
      if(poll){clearInterval(poll);poll=null;}outcome.replaceChildren();text(state,'Uploading files to external REST API…');
      const submit=form.querySelector('[type="submit"]');if(submit)submit.disabled=true;
      try{
        const raw=await request('/rest-monitor/upload',{method:'POST',body:new FormData(form),headers:csrf()});
        const job=unwrap(raw);const id=jobId(job);
        if(!id) {text(state,'Upload accepted, but the API did not return a job ID. Inspect acknowledgement below.');outcome.append(element('pre',JSON.stringify(raw,null,2)));return;}
        text(state,'Scan queued · Job '+id);
        const result=element('div',undefined,'rest-detail');outcome.append(result);
        const link=element('a','Open API Reports','btn btn-outline-light btn-sm');link.href='/api-reports';outcome.append(link);
        let attempts=0;let completed=false;
        const pollJob=async()=>{
          if(!form.isConnected){if(poll)clearInterval(poll);return;}
          attempts++;
          try {
            const detail=unwrap(await request('/rest-monitor/jobs/'+encodeURIComponent(id)));
            const record=showJob(result,detail);
            if(finished(record)){
              completed=true;
              text(state,'Scan finished · '+status(record)+' · '+(first(record,'verdict','result')||'See file results below'));
              clearInterval(poll);poll=null;
              document.querySelectorAll('[data-rest-jobs],[data-rest-summary]').forEach(el=>el.dispatchEvent(new Event('clamav:rest-refresh')));
            }
          }catch(e) {text(state,'Job '+id+' queued. Status unavailable: '+e.message);}
          if(attempts>=720 && poll) {clearInterval(poll);poll=null;text(state,'Job '+id+' queued. Polling stopped after 30 minutes; check API Reports.');}
        };
        await pollJob();if(!completed && attempts<720 && !poll)poll=setInterval(pollJob,2500);
        document.querySelectorAll('[data-rest-jobs],[data-rest-summary]').forEach(el=>el.dispatchEvent(new Event('clamav:rest-refresh')));
        files.value='';
      }catch(e){text(state,'Scan submission failed: '+e.message);state?.classList.add('rest-error');}
      finally{if(submit)submit.disabled=false;}
    });
  });}
  function init(){bindSummaries();bindJobs();bindUploads();}
  document.addEventListener('DOMContentLoaded',init);
  document.addEventListener('clamav:page-ready',init);
})();
