/*
 *   ST Mesh Details SmartApp
 *   Copyright 2020 Tony Fleisher
 *
 // /**********************************************************************************************************************************************/
 import java.security.MessageDigest
 
 definition(
	 name			: "ST-Mesh-Details",
	 namespace		: "tfleisher",
	 author			: "TonyFleisher",
	 description		: "Get Zigbee and ZWave mesh Details",
	 category		: "My Apps",
	 singleInstance	: true,
	 iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
	 iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
	 oauth: true)
 {
	 appSetting "personalToken"
 }
 /**********************************************************************************************************************************************/
 private releaseVer() { return "0.0.1" }
 private appVerDate() { return "2020-06-07" }
 private personalToken() { return appSettings.personalToken }
 /**********************************************************************************************************************************************/
 preferences {
	 page name: "startPage"
	 page name: "mainPage"
 }
 
 mappings {
	 path("/meshinfo") { action: [GET: "meshInfo"] }
	 path("/script.js") { action: [GET: "scriptController"] }
 }
 
 def startPage() {
	 if(!atomicState?.accessToken) { getAccessToken() }
	 if(!atomicState?.accessToken) {
		 return dynamicPage(name: "startPage", title: "Status Page", nextPage: "", install: false, uninstall: true) {
			 section ("Status Page:") {
				 def title = ""
				 def desc = ""
				 if(!atomicState?.accessToken) { title="OAUTH Error"; desc = "OAuth is not Enabled for ${app?.label} application.  Please click remove and review the installation directions again"; }
				 else { title="Unknown Error"; desc = "Application Status has not received any messages to display";	}
				 log.warn "Status Message: $desc"
				 paragraph title: "$title", "$desc", required: true, state: null
			 }
		 }
	 }
	 else { return mainPage() }
 }
 
 def mainPage() {
	 dynamicPage (name: "mainPage", title: "", install: true, uninstall: true) {

		 def hideBrowDesc = (atomicState?.isInstalled == true && ["embedded", "external"].contains(settings?.browserType))
		 section("Browser Type Description:", hideable: hideBrowDesc, hidden: hideBrowDesc) {
			 def embstr = "It's the most secure as the session is wiped everytime you close the view. So it will require logging in everytime you leave the view and isn't always friendly with Password managers (iOS)"
			 paragraph title: "Embedded (Recommended)", embstr
			 def extstr = "Will open the page outside the SmartThings app in your default browser. It will maintain your SmartThings until you logout. It will not force you to login everytime you leave the page and should be compatible with most Password managers. You can bookmark the Page for quick access."
			 paragraph title: "External", extstr
		 }
		 section("Browser Option:") {
			 input "browserType", "enum", title: "Browser Type", required: true, defaultValue: "embedded", submitOnChange: true, options: ["embedded":"Embedded", "external":"Mobile Browser"], image: ""
		 }
		 section("") {
			 if(settings?.browserType) {
				 href "", title: "Mesh Details", url: getLoginUrl(), style: (settings?.browserType == "external" ? "external" : "embedded"), required: false, description: "Tap Here to load the Mesh Details Web App", image: ""
			 } else {
				 paragraph title: "Browser Type Missing", "Please Select a browser type to proceed", required: true, state: null
			 }
		 }
	 }
 }

 def getLoginUrl() {
	 def r = URLEncoder.encode(getAppEndpointUrl("meshinfo"))
	 def theURL = "https://account.smartthings.com/login?redirect=${r}"
	 return theURL
 }
 
 def meshInfo() {
	 def html = """
<html lang="en">
<head>
<title>ST Mesh Details</title>
<link rel="stylesheet" type="text/css" href="https://cdn.datatables.net/1.10.21/css/jquery.dataTables.min.css">
<link rel="stylesheet" type="text/css" href="https://cdn.datatables.net/searchpanes/1.1.1/css/searchPanes.dataTables.min.css">
<link rel="stylesheet" type="text/css" href="https://cdn.datatables.net/select/1.3.1/css/select.dataTables.min.css">
<!--  
<script src="jquery-3.5.1.min.js"></script>
-->
<script src="https://ajax.googleapis.com/ajax/libs/jquery/3.5.1/jquery.min.js"></script>
<script src="${getAppEndpointUrl("script.js")}"></script>
</head>
<body>
<h1 style="text-align:center;">ST Mesh Details</h1>
<div id="messages"><div id="loading1" style="text-align:center;"></div><div id="loading2" style="text-align:center;"></div></div>
<table id="mainTable" class="stripe cell-border hover">
	 <thead>
	 	<tr>
	   </tr>
	 </thead>
</table>
</body>
</html>
	 """
	 render contentType: "text/html", data: html
 }
 
 def scriptController() {
	 def javaScript = """
 var token="${personalToken()}"
 var stAPIUrl="https://api.smartthings.com/v1/"
 var mySTHost = "${apiServerUrl("/")}"
 const MAX_IDE_DEV_COUNT=100;
 
 function loadScripts() {
 	 updateLoading('Loading...','Getting script sources');
	 var s1 = \$.getScript('https://unpkg.com/axios/dist/axios.min.js', function() {
		 console.log("axios loaded")
	 });
	 
	 var s2 = \$.getScript('https://cdn.datatables.net/1.10.21/js/jquery.dataTables.min.js')
 		.then(s => \$.getScript('https://cdn.datatables.net/select/1.3.1/js/dataTables.select.min.js'))
 		.then(s => \$.getScript('https://cdn.datatables.net/searchpanes/1.1.1/js/dataTables.searchPanes.min.js'));
	 return Promise.all([s1, s2]);
 }
 function getDeviceList(personalToken) {
	 const instance = axios.create({
		   baseURL: stAPIUrl,
		   timeout: 35000,
		   headers: {'Authorization': 'Bearer ' + personalToken}
		 });
	 if ("null" === personalToken) {
		alert('The Personal Token needs to be set in the smartApp settings from the IDE');
		return Promise.reject();
	 }
	 return instance
	 .get('/devices')
	 .then(response => {
	   return response.data.items
	   .filter(checkNetwork)
	   .map( element => {
		   var result = transformDevice(element);
		   return result;
	   })
	 })
	 .catch(error => console.error(error));
 }
 
 function checkNetwork(device) {
	 return (device.deviceNetworkType === "ZIGBEE" || device.deviceNetworkType === "ZWAVE")
 }
 var ideDevCount = 0;
 function transformDevice(device){
	 var capabilities = [];
	 device.components.forEach(c => {
		 if (c.id === 'main') {
			 c.capabilities.forEach(cap => {
				 capabilities.push(cap.id)
			 })
		 }
	 });
	 var deviceData = {
			 id: device.deviceId,
			 name: device.name,
			 room: device.roomId,
			 label: device.label,
			 capabilities: capabilities,
			 networkType: device.deviceNetworkType,
		 };
	 var ideDeviceData = {};
	 if (ideDevCount < MAX_IDE_DEV_COUNT) {
		 //console.log("Testing IDE request");
		 ideDeviceData = getIDEDevice(device);
		 ideDevCount = ideDevCount + 1;
		 return ideDeviceData.then(ideData => {
			 Object.keys(ideData).forEach(e => deviceData[e] = ideData[e]);
			 return deviceData;
		 });
	 }
	 return Promise.resolve(deviceData);
 }
 var r;
 function getIDEDevice(device) {
   const deviceId = device.deviceId;
   const deviceNetworkType = device.deviceNetworkType;
   const label = device.label;
   const instance = axios.create({
		   baseURL: mySTHost,
		   responseType: "document",
		   timeout: 15000
		 });
	 
   return instance
   .get(`/device/show/\${deviceId}`)
   .then(response => {
		   // console.log(response);
	   r = response
		   var result = {}
		   var doc = new jQuery(response.data)
	 	 var routeLabel = doc.find('#meshRoute-label');
		 var routesHtmlLinks = routeLabel.parent().find('.property-value a')
	 	 var routeHtml = routeLabel.parent().find('.property-value')[0].innerHTML.toString();
	 	 //console.log(routeHtml);
	 	 var cleanRouteHtml = routeHtml.replace(/[)] /g,")").replace(/This Device/g, label);
	 	 //console.log(cleanRouteHtml);
		 var theDevice = routesHtmlLinks[0];
		 var theParent = routesHtmlLinks[1];
		 var theParentName = theParent.text.trim();
		 var theParentHref = theParent.href.trim();
		 var theParentId = theParent.href.substring(theParent.href.lastIndexOf('/') + 1);
		 
	 	 var routers = routesHtmlLinks.toArray().filter( (n,i) => i > 0).map( (e) => e.text.trim()); 
	 	 //console.log(`Device: \${deviceId} parent: \${theParentName}(\${theParentId})`);
		 result = {
				 parentName: theParentName,
				 parentId: theParentId,
	 			 routeHtml: cleanRouteHtml,
	 			 routers: routers
			 };
		 result.metrics = {};
		 doc.find('#deviceMetrics-label').parent().find('.property-value li')
			 .each((ii,jj) => {
				 const metric = jj.innerText.trim();
				 const firstC = metric.indexOf(':'); // Fragile: This assumes
													 // metric labels don't have
													 // ':' character
				 const m1 = metric.substring(0,firstC);
				 const m2 = metric.substring(firstC + 1);
				 const m = m1.replace(/[ ()]/g, "").trim();
				 const v = m2.trim();
				 result.metrics[m] = v;
			 });
		 //console.log(result);
		 return result;
   });
 }

function updateLoading(msg1, msg2) {
	 \$('#loading1').text(msg1);
 	 \$('#loading2').text(msg2);
}
 
 \$.ajaxSetup({
	   cache: true
	 });
 var tableContent;
 var tableHandle;
 \$(document).ready(function(){
		 loadScripts().then(function() {
			 updateLoading('Loading..','Getting device data');
			 getDeviceList(token).then(list => {
				 // console.log(list)
				 updateLoading('Loading.','Getting device detail');
				 Promise.all(list).then(r => {
					 var deviceMap = r.reduce( (acc,val) => {
						 acc[val.id]=val;
						 return acc;
					 }, {});
					 
				 	updateLoading('Loading..','Building maps');
					 r.forEach(d => {
						 const p = deviceMap[d.parentId];
						 if (p != undefined) {
							 if (p.children === undefined) {
								 p.children = [];
							 }
							 p.children.push(d.label);
						 }
					 });
					 tableContent = r;
					 //console.log(deviceMap);
					 updateLoading('Loading..','Creating table');
					 tableHandle = \$('#mainTable').DataTable({
						 data: tableContent,
	 					 order: [[2,'asc']],
						 columns: [
							 { data: 'networkType', title: 'Type', searchPanes: { preSelect:['ZWAVE','ZIGBEE']} },
							 { data: 'id', title: 'Device id' },
							 { data: 'label', title: 'Device name' },
	 						 { data: 'routeHtml', title: 'Route' },
							 { data: 'parentName', title: 'Next Hop', visible: false },
	 						 { data: 'routers', title: 'Routers', visible: false,
								render: {'_':'[, ]', sp: '[]'},
								defaultContent: "None",
								searchPanes : { orthogonal: 'sp' }
							},
	 						 { data: 'metrics.LastHopRSSI', title: 'Last Hop RSSI', defaultContent: "n/a", searchPanes: {show: false} },

							/*{ data: 'children', title: 'Consumers',
								render: {'_':'[, ]', sp: '[]'},
								defaultContent: "None",
								searchPanes : { orthogonal: 'sp' }
							} */
						 ],
						 "pageLength": -1,
						 "rowId": 'id',
 						 "lengthChange": false,
						 "paging": false,
						 "dom": "Pftrip",
						 "searchPanes": {
 							cascadePanes: true
					 	  }
					 });
				 	updateLoading('','');

				 });
			 });
		 });
 });
"""
	 render contentType: "application/javascript", data: javaScript
	 
 }
 
 def installed() {
	 log.debug "Installed with settings: ${settings}"
	 atomicState?.isInstalled = true
	 initialize()
 }
 
 def updated() {
	 log.trace ("${app?.getLabel()} | Now Running Updated() Method")
	 if(!atomicState?.isInstalled) { atomicState?.isInstalled = true }
	 initialize()
 }
 
 def initialize() {
	 if (!atomicState?.accessToken) {
		 log.debug "Access token not defined. Attempting to refresh. Ensure OAuth is enabled in the SmartThings IDE."
		 getAccessToken()
	 }
 	 log.debug "Endpoint (redirect): ${getLoginUrl()}"
	 log.debug "Endpoint: ${getAppEndpointUrl('meshinfo')}"
}
 
 def uninstalled() {
	 revokeAccessToken()
	 log.warn("${app?.getLabel()} has been Uninstalled...")
 }
 
 def generateLocationHash() {
	 def s = location?.getId()
	 MessageDigest digest = MessageDigest.getInstance("MD5")
	 digest.update(s.bytes);
	 new BigInteger(1, digest.digest()).toString(16).padLeft(32, '0')
 }
 
 def getAccessToken() {
	 try {
		 if(!atomicState?.accessToken) {
			 log.error "SmartThings Access Token Not Found... Creating a New One!!!"
			 atomicState?.accessToken = createAccessToken()
		 } else { return true }
	 }
	 catch (ex) {
		 log.error "Error: OAuth is not Enabled for ${app?.label}!.  Please click remove and Enable Oauth under the SmartApp App Settings in the IDE"
		 return false
	 }
 }
 
 def gitBranch()         { return "master" }
 def getAppEndpointUrl(subPath)	{ return "${apiServerUrl("/api/smartapps/installations/${app.id}${subPath ? "/${subPath}" : ""}?access_token=${atomicState.accessToken}")}" }
 
 