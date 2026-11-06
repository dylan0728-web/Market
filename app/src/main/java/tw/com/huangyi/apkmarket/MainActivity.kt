<!DOCTYPE html>
<html lang="zh-Hant">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Market</title>

<style>
*{box-sizing:border-box}

body{
  margin:0;
  color:#fff;
  font-family:Arial,"Microsoft JhengHei",sans-serif;
  background:linear-gradient(135deg,#101820,#202d38);
}

header{
  min-height:76px;
  padding:15px 25px;
  display:flex;
  align-items:center;
  justify-content:space-between;
  background:#111b24;
  border-bottom:1px solid #40505d;
}

header h2{margin:0;font-size:25px}

#status{
  color:#aebdca;
  font-size:14px;
  margin-left:12px;
}

main{padding:25px}

#message{
  color:#ffcf70;
  margin-bottom:20px;
}

#apps{
  display:grid;
  grid-template-columns:repeat(3,minmax(0,1fr));
  gap:16px;
}

.app{
  min-height:125px;
  padding:18px;
  display:flex;
  align-items:center;
  gap:14px;
  background:#293945;
  border:2px solid #405461;
  border-radius:12px;
  cursor:pointer;
}

.app:focus,.app.selected{
  outline:none;
  border-color:#fff;
  background:#3b5363;
  transform:scale(1.02);
}

.appVisual{
  width:76px;
  flex-shrink:0;
  display:flex;
  flex-direction:column;
  align-items:center;
  gap:7px;
}

.appIcon{
  width:64px;
  height:64px;
  border-radius:12px;
  display:flex;
  align-items:center;
  justify-content:center;
  background:#526b7b;
  font-size:15px;
  font-weight:bold;
  overflow:hidden;
}

.appIcon img{
  width:100%;
  height:100%;
  object-fit:contain;
}

.downloadArea{
  width:76px;
  display:none;
  flex-direction:column;
  align-items:center;
  gap:4px;
}

.downloadArea.show{display:flex}

.downloadTrack{
  width:100%;
  height:6px;
  background:#17232c;
  border-radius:10px;
  overflow:hidden;
}

.downloadBar{
  width:0%;
  height:100%;
  background:#36a9ee;
  border-radius:10px;
  transition:width .3s ease;
}

.downloadText{
  width:100%;
  font-size:11px;
  color:#b8dfff;
  text-align:center;
  white-space:nowrap;
}

.appInfo{
  min-width:0;
  flex:1;
}

.appName{
  font-size:17px;
  font-weight:bold;
  word-break:break-word;
}

.appInfoText{
  font-size:13px;
  color:#b8c6cf;
  margin-top:8px;
  word-break:break-word;
}

.appState{
  display:inline-block;
  margin-top:7px;
  padding:4px 0;
  color:#b8c6cf;
  font-size:13px;
}

.appState.installed{color:#56d99a}
.appState.downloading{color:#69c5ff}

/* 已安裝軟體操作視窗 */
.modal{
  display:none;
  position:fixed;
  inset:0;
  z-index:1000;
  background:rgba(0,0,0,.75);
  align-items:center;
  justify-content:center;
  padding:20px;
}

.modal.show{display:flex}

.modalBox{
  width:100%;
  max-width:360px;
  padding:24px;
  border-radius:15px;
  background:#263945;
  border:1px solid #607786;
  text-align:center;
}

.modalTitle{
  font-size:20px;
  font-weight:bold;
  margin-bottom:10px;
}

.modalDesc{
  color:#bdcbd4;
  font-size:14px;
  margin-bottom:22px;
}

.modalButton{
  width:100%;
  padding:13px;
  margin-top:10px;
  border:0;
  border-radius:8px;
  color:#fff;
  font-size:16px;
  cursor:pointer;
}

.openButton{background:#168653}
.uninstallButton{background:#a83b3b}

button{
  color:white;
  background:#334b5a;
  border:1px solid #718796;
  padding:9px 16px;
  border-radius:8px;
  cursor:pointer;
}

@media(max-width:700px){
  #apps{grid-template-columns:repeat(2,minmax(0,1fr))}
}

@media(max-width:450px){
  #apps{grid-template-columns:1fr}
}
</style>
</head>

<body>

<header>
  <h2>Market</h2>
  <div>
    <button onclick="loadApps()">重新整理</button>
    <span id="status">APK 市場</span>
  </div>
</header>

<main>
  <div id="message">正在讀取 APK 清單...</div>
  <div id="apps"></div>
</main>

<!-- 已安裝軟體操作選單 -->
<div class="modal" id="appModal">
  <div class="modalBox">
    <div class="modalTitle" id="modalTitle">此軟體已安裝</div>
    <div class="modalDesc" id="modalDesc">請選擇操作</div>

    <button class="modalButton openButton" onclick="openSelectedApp()">
      開啟軟體
    </button>

    <button class="modalButton uninstallButton" onclick="uninstallSelectedApp()">
      卸載／刪除軟體
    </button>
  </div>
</div>

<script>
var appsElement=document.getElementById("apps");
var messageElement=document.getElementById("message");
var statusElement=document.getElementById("status");

var appCards={};
var selectedApp=null;

function formatSize(bytes){
  if(!bytes)return "大小未知";
  return (bytes/1024/1024).toFixed(1)+" MB";
}

/* 讀取中文名稱及套件資料 */
function loadNames(){
  return fetch("apps.json?time="+Date.now())
    .then(function(response){
      if(!response.ok)throw new Error("apps.json 尚未建立");
      return response.json();
    })
    .catch(function(){
      return [];
    });
}

function findAppInfo(list,fileName){
  for(var i=0;i<list.length;i++){
    if(list[i].fileName===fileName){
      return list[i];
    }
  }
  return {};
}

function loadApps(){

  appsElement.innerHTML="";
  appCards={};
  selectedApp=null;

  document.getElementById("appModal").classList.remove("show");

  messageElement.textContent="正在讀取 APK 清單...";
  statusElement.textContent="讀取中";

  Promise.all([
    fetch("https://api.github.com/repos/dylan0728-web/Market/releases")
      .then(function(response){
        if(!response.ok)throw new Error("GitHub API "+response.status);
        return response.json();
      }),
    loadNames()
  ])

  .then(function(results){

    var releases=results[0];
    var names=results[1];
    var count=0;

    releases.forEach(function(release){

      (release.assets||[]).forEach(function(asset){

        if(!asset.name.toLowerCase().endsWith(".apk"))return;

        count++;

        var appInfo=findAppInfo(names,asset.name);

        var displayName=appInfo.name||asset.name;
        var packageName=appInfo.packageName||"";

        var card=document.createElement("div");
        card.className="app";
        card.tabIndex=0;

        var visual=document.createElement("div");
        visual.className="appVisual";

        var icon=document.createElement("div");
        icon.className="appIcon";
        icon.textContent="APK";

        var downloadArea=document.createElement("div");
        downloadArea.className="downloadArea";

        var track=document.createElement("div");
        track.className="downloadTrack";

        var bar=document.createElement("div");
        bar.className="downloadBar";

        track.appendChild(bar);

        var percentText=document.createElement("div");
        percentText.className="downloadText";
        percentText.textContent="";

        downloadArea.appendChild(track);
        downloadArea.appendChild(percentText);

        visual.appendChild(icon);
        visual.appendChild(downloadArea);

        var info=document.createElement("div");
        info.className="appInfo";

        var name=document.createElement("div");
        name.className="appName";
        name.textContent=displayName;

        var detail=document.createElement("div");
        detail.className="appInfoText";
        detail.textContent="版本："+release.tag_name+
          " · "+formatSize(asset.size);

        var state=document.createElement("div");
        state.className="appState";
        state.textContent="點擊使用";

        info.appendChild(name);
        info.appendChild(detail);
        info.appendChild(state);

        card.appendChild(visual);
        card.appendChild(info);

        var app={
          url:asset.browser_download_url,
          fileName:asset.name,
          name:displayName,
          packageName:packageName,
          icon:icon,
          bar:bar,
          downloadArea:downloadArea,
          percentText:percentText,
          detail:detail,
          state:state,
          ready:true
        };

        appCards[app.url]=app;

        card.addEventListener("click",function(){
          handleAppClick(app);
        });

        appsElement.appendChild(card);

      });

    });

    if(count===0){
      messageElement.textContent="目前沒有 APK，請先上傳 APK 到 GitHub Releases。";
      statusElement.textContent="沒有 APK";
      return;
    }

    messageElement.textContent="共找到 "+count+" 個軟體";
    statusElement.textContent="市場已就緒";

  })

  .catch(function(error){
    messageElement.textContent="讀取失敗："+error.message;
    statusElement.textContent="讀取失敗";
  });
}

/* 點擊時才檢查這一個軟體 */
function handleAppClick(app){

  if(window.AndroidMarket &&
     typeof window.AndroidMarket.checkInstalled==="function"){

    selectedApp=app;
    messageElement.textContent="正在確認 "+app.name+"...";

    window.AndroidMarket.checkInstalled(
      app.packageName,
      app.url
    );

  }else{
    startAppDownload(app);
  }
}

/* Android 回傳單一軟體的安裝狀態 */
window.onAppCheckResult=function(url,installed,appLabel,iconBase64){

  var app=appCards[url];
  if(!app)return;

  if(iconBase64){
    app.icon.innerHTML="";

    var img=document.createElement("img");
    img.src="data:image/png;base64,"+iconBase64;
    app.icon.appendChild(img);
  }

  if(installed){

    selectedApp=app;

    document.getElementById("modalTitle").textContent=
      (appLabel||app.name)+" 已安裝";

    document.getElementById("modalDesc").textContent=
      "請選擇要開啟或卸載此軟體";

    document.getElementById("appModal").classList.add("show");

    app.state.textContent="已安裝";
    app.state.className="appState installed";

  }else{

    startAppDownload(app);

  }

};

/* 開啟已安裝軟體 */
function openSelectedApp(){

  if(!selectedApp)return;

  document.getElementById("appModal").classList.remove("show");

  if(window.AndroidMarket){
    window.AndroidMarket.launchApp(selectedApp.packageName);
  }

}

/* 卸載已安裝軟體 */
function uninstallSelectedApp(){

  if(!selectedApp)return;

  document.getElementById("appModal").classList.remove("show");

  if(window.AndroidMarket){
    window.AndroidMarket.uninstallApp(selectedApp.packageName);
  }

}

/* 尚未安裝才開始下載 */
function startAppDownload(app){

  if(!app)return;

  selectedApp=null;

  app.state.textContent="下載中";
  app.state.className="appState downloading";

  app.downloadArea.classList.add("show");
  app.percentText.textContent="0%";
  app.bar.style.width="0%";

  if(window.AndroidMarket &&
     typeof window.AndroidMarket.startDownload==="function"){

    window.AndroidMarket.startDownload(app.url,app.fileName);

  }else{

    window.open(app.url,"_blank");

  }

}

/* 每個軟體獨立下載進度 */
window.onApkDownloadProgress=function(url,percent,state){

  var app=appCards[url];
  if(!app)return;

  percent=Math.max(0,Math.min(100,Number(percent)||0));

  app.downloadArea.classList.add("show");
  app.bar.style.width=percent+"%";

  if(state==="complete"){

    app.percentText.textContent="下載完成";
    app.state.textContent="準備安裝";
    app.detail.textContent="下載完成，準備安裝";

  }else if(state==="failed"){

    app.downloadArea.classList.remove("show");
    app.percentText.textContent="";
    app.bar.style.width="0%";

    app.state.textContent="下載失敗";
    app.state.className="appState";

  }else{

    app.percentText.textContent="下載中 "+percent+"%";
    app.state.textContent="下載中";
    app.state.className="appState downloading";

  }

};

/* 安裝完成後的狀態更新 */
window.onApkInstallResult=function(url,success){

  var app=appCards[url];
  if(!app)return;

  if(success){
    app.state.textContent="安裝完成";
    app.state.className="appState installed";
    app.detail.textContent="已完成安裝";
  }

};

/* 電視盒方向鍵 */
document.addEventListener("keydown",function(event){

  if(document.getElementById("appModal").classList.contains("show")){
    if(event.key==="Escape" || event.key==="Backspace"){
      document.getElementById("appModal").classList.remove("show");
      selectedApp=null;
    }
    return;
  }

  var cards=Array.prototype.slice.call(
    document.querySelectorAll(".app")
  );

  if(!cards.length)return;

  var current=cards.indexOf(document.activeElement);
  if(current<0)current=0;

  var next=current;

  if(event.key==="ArrowRight"){
    next=Math.min(current+1,cards.length-1);
  }else if(event.key==="ArrowLeft"){
    next=Math.max(current-1,0);
  }else if(event.key==="ArrowDown"){
    next=Math.min(current+3,cards.length-1);
  }else if(event.key==="ArrowUp"){
    next=Math.max(current-3,0);
  }else{
    return;
  }

  event.preventDefault();
  cards[next].focus();

});

loadApps();

</script>
</body>
</html>
