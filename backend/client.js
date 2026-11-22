(() => {
  "use strict";
  const el = id => document.getElementById(id);
  const all = selector => Array.from(document.querySelectorAll(selector));
  let catalog = {series:[],episodes:[]};
  let pendingImport = null, installPrompt = null, email = "";
  function status(id,message,error=false) {
    const target=el(id);
    if (target) {target.textContent=message;target.classList.toggle("error",!!error);}
  }
  async function api(path,method="GET",body) {
    const options={method,credentials:"same-origin",headers:{"Accept":"application/json"}};
    if(body !== undefined) {
      if(body instanceof Blob) {
        options.body=body;options.headers["Content-Type"]=body.type||"application/octet-stream";
      } else {
        options.body=JSON.stringify(body);
        options.headers["Content-Type"]="application/json";
      }
    }
    const response=await fetch(path,options);
    const payload=await response.json().catch(()=>({error:"Unexpected response from Studio"}));
    if(!response.ok) {
      if(response.status===401 && !path.startsWith("/v1/auth/")) {
        showAuth(false);
        status("authNotice","Your session has expired. Sign in again.",true);
      }
      throw Error(payload.error || "Request failed (HTTP "+response.status+")");
    }
    return payload;
  }
  function showAuth(setupRequired) {
    el("appShell").hidden=true;
    el("authScreen").hidden=false;
    el("setupForm").hidden=!setupRequired;
    el("loginForm").hidden=setupRequired;
    el("authTitle").textContent=setupRequired?"Secure your Studio.":"Welcome back.";
    el("authDescription").textContent=setupRequired?
      "Create the AddisReel owner account. You'll need your existing admin token only this once.":
      "Sign in with your email and password. Your device will remember the session.";
  }
  async function showApp(accountEmail) {
    email=accountEmail;
    el("appShell").hidden=false;
    el("authScreen").hidden=true;
    el("accountEmail").textContent=email;
    el("avatar").textContent=(email.charAt(0)||"A").toUpperCase();
    selectPage("overview");
    await refresh();
  }
  function selectPage(name) {
    if(!["overview","library","create","media","account"].includes(name)) name="overview";
    all("[data-page]").forEach(node=>node.classList.toggle("active",node.dataset.page===name));
    all("[data-tab]").forEach(node=>node.classList.toggle("active",node.dataset.tab===name));
    el("currentPage").textContent=({overview:"Overview",library:"Content library",create:"Create & edit",media:"Media uploads",account:"My account"})[name];
    if(typeof window.scrollTo==="function")window.scrollTo({top:0,behavior:"instant"});
  }
  function node(tag,cls,text) {
    const elem=document.createElement(tag);
    if(cls)elem.className=cls;
    if(text!==undefined)elem.textContent=text;
    return elem;
  }
  function button(label,fn) {
    const b=node("button","btn secondary small",label);
    b.type="button";b.addEventListener("click",fn);return b;
  }
  function editSeries(s) {
    el("sid").value=s.id;el("stitle").value=s.title;el("sgenre").value=s.genre;
    el("sdesc").value=s.description||"";el("ssort").value=s.sort_order||0;
    el("sstatus").value=s.status;el("slicensed").checked=!!s.licensed;
    el("eid").value=s.id;el("mediaSeries").value=s.id;el("hlsSeries").value=s.id;
    selectPage("create");
    status("createNotice","Editing "+s.title+". Save to apply changes.");
  }
  function editEpisode(s,e) {
    editSeries(s);
    el("enum").value=e.number;el("etitle").value=e.title;
    el("eduration").value=e.duration_seconds||0;
    el("estatus").value=e.status;el("elicensed").checked=!!e.licensed;
    el("hlsEpisode").value=e.number;
    status("createNotice","Editing episode "+e.number+" of "+s.title+".");
  }
  function renderRow(s,includeEpisodes) {
    const row=node("div","movie");
    const art=node("div","poster","🎞");
    art.setAttribute("aria-hidden","true");
    const inner=node("div","movieBody");
    inner.append(node("div","movieTitle",s.title));
    const count=catalog.episodes.filter(e=>e.series_id===s.id).length;
    inner.append(node("div","movieMeta",s.genre+" · "+count+" episode"+(count===1?"":"s")));
    const chip=node("span","pill "+(s.status==="published"&&s.licensed?"green":s.status==="published"?"red":"gold"),
      s.status==="published"&&s.licensed?"● Published / rights verified":s.status==="published"?"● Published / rights unverified":"● Draft");
    inner.append(chip);
    const actions=node("div","movieActions");
    actions.append(button("Edit series",()=>editSeries(s)));
    if(includeEpisodes) {
      const episodes=catalog.episodes.filter(e=>e.series_id===s.id).sort((a,b)=>a.number-b.number);
      for(const e of episodes)actions.append(button("Ep "+e.number+" · Edit",()=>editEpisode(s,e)));
    }
    inner.append(actions);
    row.append(art,inner);
    return row;
  }
  async function refresh() {
    try {
      const data=await api("/v1/admin/series");
      catalog={series:data.series||[],episodes:data.episodes||[]};
      el("metricSeries").textContent=catalog.series.length;
      el("metricEpisodes").textContent=catalog.episodes.length;
      el("metricPublished").textContent=catalog.series.filter(s=>s.status==="published").length;
      el("metricDrafts").textContent=catalog.series.filter(s=>s.status==="draft").length;
      const recent=el("recentList"),list=el("catalogList");
      recent.replaceChildren();list.replaceChildren();
      if(!catalog.series.length) {
        recent.append(node("p","muted","No titles yet. Create your first Ethiopian short-drama series."));
        list.append(node("p","muted","Your catalog is empty. Select Create & edit to add a drama."));
      } else {
        for(const s of catalog.series.slice(0,3))recent.append(renderRow(s,false));
        for(const s of catalog.series)list.append(renderRow(s,true));
      }
      el("connectionStatus").textContent="● Connected";
    } catch(error) {
      if(!el("authScreen").hidden)return;
      el("connectionStatus").textContent="● Offline";
      status("createNotice",error.message,true);
    }
  }
  function fillFromCopy(raw) {
    try {
      const entry=window.parseMovieFields(raw);
      el("importText").value=raw;
      el("sid").value=entry.slug;el("stitle").value=entry.title;
      el("sgenre").value=entry.genre;el("sdesc").value=entry.description;
      el("ssort").value=entry.sortOrder;
      el("eid").value=entry.slug;el("enum").value=entry.episodeNumber;
      el("etitle").value=entry.episodeTitle;el("eduration").value=entry.durationSeconds;
      el("sstatus").value="draft";el("estatus").value="draft";
      el("slicensed").checked=false;el("elicensed").checked=false;
      el("mediaSeries").value=entry.slug;
      el("hlsSeries").value=entry.slug;el("hlsEpisode").value=entry.episodeNumber;
      pendingImport=entry;el("saveImported").disabled=false;
      el("importPreview").textContent="READY TO SAVE AS DRAFTS\n"+entry.title+" · "+entry.genre+
        "\nEpisode "+entry.episodeNumber+": "+entry.episodeTitle+"\nSeries ID: "+entry.slug;
      status("importStatus","✓ Both forms filled. Review and save, or edit any field.");
    } catch(error) {
      pendingImport=null;el("saveImported").disabled=true;el("importPreview").textContent="";
      status("importStatus",error.message,true);
    }
  }
  async function saveImported() {
    if(!pendingImport)return;
    let entry;
    try {
      entry=window.parseMovieFields([
        "Series slug: "+el("sid").value,"Title: "+el("stitle").value,
        "Genre: "+el("sgenre").value,"Description: "+el("sdesc").value.replace(/\r?\n/g," "),
        "Sort order: "+el("ssort").value,"Episode series slug: "+el("eid").value,
        "Episode number: "+el("enum").value,"Episode title: "+el("etitle").value,
        "Duration seconds: "+el("eduration").value
      ].join("\n"));
    } catch(error) {status("importStatus",error.message,true);return;}
    const save=el("saveImported");save.disabled=true;
    try {
      const existing=await api("/v1/admin/series");
      const s=(existing.series||[]).find(item=>item.id===entry.slug);
      const e=(existing.episodes||[]).find(item=>item.series_id===entry.slug&&Number(item.number)===entry.episodeNumber);
      if(s&&s.title.trim()!==entry.title.trim())throw Error("Different title already uses this series ID. Edit the existing series.");
      if(e&&e.title.trim()!==entry.episodeTitle.trim())throw Error("That episode already has another title. Edit it from the Library.");
      if(!s)await api("/v1/admin/series","POST",{id:entry.slug,title:entry.title,
        genre:entry.genre,description:entry.description,sortOrder:entry.sortOrder,status:"draft",licensed:false});
      if(!e)await api("/v1/admin/episodes","POST",{seriesId:entry.slug,number:entry.episodeNumber,
        title:entry.episodeTitle,durationSeconds:entry.durationSeconds,status:"draft",licensed:false});
      pendingImport=null;
      await refresh();
      status("importStatus",s&&e?"✓ Already saved! Nothing duplicated or changed.":
        s?"✓ Existing series reused; missing episode saved as a draft.":"✓ Series and episode saved as drafts.");
    } catch(error) {
      save.disabled=false;status("importStatus","Save not completed: "+error.message,true);
    }
  }
  function setupEvents() {
    all("[data-tab]").forEach(btn=>btn.addEventListener("click",()=>selectPage(btn.dataset.tab)));
    all("[data-go]").forEach(btn=>btn.addEventListener("click",()=>selectPage(btn.dataset.go)));
    el("setupForm").addEventListener("submit",async event=>{
      event.preventDefault();status("authNotice","Setting up your account…");
      try {
        const result=await api("/v1/auth/setup","POST",{
          setupToken:el("setupToken").value,email:el("setupEmail").value,password:el("setupPassword").value
        });
        el("setupToken").value="";el("setupPassword").value="";
        status("authNotice","");
        await showApp(result.email);
      } catch(error){status("authNotice",error.message,true)}
    });
    el("loginForm").addEventListener("submit",async event=>{
      event.preventDefault();status("authNotice","Signing in…");
      try {
        const result=await api("/v1/auth/login","POST",{
          email:el("loginEmail").value,password:el("loginPassword").value
        });
        el("loginPassword").value="";status("authNotice","");
        await showApp(result.email);
      } catch(error){status("authNotice",error.message,true)}
    });
    el("logoutButton").addEventListener("click",async()=>{
      try {
        await api("/v1/auth/logout","POST",{});
        catalog={series:[],episodes:[]};pendingImport=null;
        el("loginPassword").value="";
        showAuth(false);
        status("authNotice","Signed out successfully. Sign in with your password.");
      } catch(error){status("accountNotice",error.message,true)}
    });
    el("pasteMovie").addEventListener("click",async()=>{
      try {
        if(!navigator.clipboard?.readText)throw Error("Clipboard is not available here");
        fillFromCopy(await navigator.clipboard.readText());
      } catch(error) {
        status("importStatus","Use the text box below: long-press, select Paste, and both forms will fill.",true);
        el("importText").focus();
      }
    });
    el("importText").addEventListener("paste",event=>{
      const text=event.clipboardData?.getData("text/plain");
      if(text){event.preventDefault();fillFromCopy(text)}
    });
    el("importText").addEventListener("input",()=>{pendingImport=null;el("saveImported").disabled=true});
    el("fillMovie").addEventListener("click",()=>fillFromCopy(el("importText").value));
    el("saveImported").addEventListener("click",saveImported);
    el("seriesForm").addEventListener("submit",async event=>{
      event.preventDefault();
      try {
        await api("/v1/admin/series","POST",{
          id:el("sid").value,title:el("stitle").value,genre:el("sgenre").value,
          description:el("sdesc").value,sortOrder:Number(el("ssort").value),
          status:el("sstatus").value,licensed:el("slicensed").checked
        });
        await refresh();status("createNotice","✓ Series saved.");
      } catch(error){status("createNotice",error.message,true)}
    });
    el("episodeForm").addEventListener("submit",async event=>{
      event.preventDefault();
      try {
        await api("/v1/admin/episodes","POST",{
          seriesId:el("eid").value,number:Number(el("enum").value),
          title:el("etitle").value,durationSeconds:Number(el("eduration").value),
          status:el("estatus").value,licensed:el("elicensed").checked
        });
        await refresh();status("createNotice","✓ Episode saved.");
      } catch(error){status("createNotice",error.message,true)}
    });
    el("uploadPoster").addEventListener("click",async()=>{
      const file=el("poster").files?.[0];
      if(!file){status("mediaNotice","Choose a poster first.",true);return}
      const series=el("mediaSeries").value;
      if(!/^[a-z0-9][a-z0-9-]*$/.test(series)){status("mediaNotice","Enter a valid series ID.",true);return}
      const btn=el("uploadPoster");btn.disabled=true;
      try {
        await api("/v1/admin/posters/"+encodeURIComponent(series),"PUT",file);
        status("mediaNotice","✓ Poster uploaded for "+series+".");
      }catch(error){status("mediaNotice",error.message,true)}
      finally{btn.disabled=false}
    });
    el("uploadHls").addEventListener("click",async()=>{
      const series=el("hlsSeries").value,number=Number(el("hlsEpisode").value);
      const files=Array.from(el("hlsFiles").files||[]);
      if(!/^[a-z0-9][a-z0-9-]*$/.test(series)||!Number.isInteger(number)||number<1||!files.length){
        status("mediaNotice","Choose a valid series, episode and HLS directory.",true);return
      }
      const btn=el("uploadHls");btn.disabled=true;
      try {
        let uploaded=0;
        for(let i=0;i<files.length;i++) {
          const f=files[i],rel=f.webkitRelativePath||f.name;
          const parts=rel.split("/"),path=parts.length>1?parts.slice(1).join("/"):rel;
          if(!/\.(m3u8|ts|m4s|mp4)$/.test(path))continue;
          el("uploadState").textContent="Uploading "+(i+1)+"/"+files.length+" · "+path;
          await api("/v1/admin/hls/"+encodeURIComponent(series)+"/"+number+"/"+path.split("/").map(encodeURIComponent).join("/"),"PUT",f);
          uploaded++;
        }
        el("uploadState").textContent="✓ Uploaded "+uploaded+" streaming files. Confirm master.m3u8 before publishing.";
        status("mediaNotice","✓ Upload complete.");
      }catch(error){status("mediaNotice",error.message,true)}
      finally{btn.disabled=false}
    });
    window.addEventListener("beforeinstallprompt",event=>{
      event.preventDefault();installPrompt=event;
    });
    el("installButton").addEventListener("click",async()=>{
      if(installPrompt){
        installPrompt.prompt();
        await installPrompt.userChoice;
        installPrompt=null;
      } else {
        status("accountNotice","To install on Android Chrome: open the browser menu (⋮) → Add to Home screen → Install app. If already installed, open AddisReel Studio from your home screen.");
      }
    });
  }
  async function start() {
    setupEvents();
    try {
      const session=await api("/v1/auth/session");
      if(session.authenticated)await showApp(session.email);
      else showAuth(session.setupRequired);
    }catch(error){
      showAuth(false);status("authNotice","Could not contact Studio. Check your connection and refresh.",true);
    }
    if("serviceWorker" in navigator) {
      navigator.serviceWorker.register("/admin/sw.js",{scope:"/admin"}).catch(()=>{});
    }
  }
  if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",start,{once:true});
  else start();
})();
