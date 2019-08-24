/*
bleBox Device Integration Application, Version 0/1
		Copyright 2018, 2019 Dave Gutheinz
Licensed under the Apache License, Version 2.0 (the "License"); you may not use this  file 
except in compliance with the License. You may obtain a copy of the License at: 
		http://www.apache.org/licenses/LICENSE-2.0.
Unless required by applicable law or agreed to in writing,software distributed under the 
License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, 
either express or implied. See the License for the specific language governing permissions 
and limitations under the License.

Usage: Use this code freely without reference to origination.
===== History =====
08.14.19	Various edits.
aa============================================================================================*/
def appVersion() { return "1.0.02" }
import groovy.json.JsonSlurper
definition(
	name: "bleBox Integration",
	namespace: "davegut",
	author: "Dave Gutheinz",
	description: "Application to install bleBox devices.",
	category: "Convenience",
	iconUrl: "",
	iconX2Url: "",
	singleInstance: true,
	importUrl: "https://raw.githubusercontent.com/DaveGut/bleBox-Hubitat/master/Application/blebox%20Manager%20App.groovy"
	)
preferences {
	page(name: "mainPage")
	page(name: "addDevicesPage")
	page(name: "listDevicesPage")
}
def setInitialStates() {
	logDebug("setInitialStates")
	app?.removeSetting("selectedAddDevices")
}
def installed() {
	if (!state.devices) { state.devices = [:] }
	if (!state.deviceIps) { state.deviceIps = [:] }
	app?.updateSetting("automaticIpPolling", [type:"bool", value: false])
	app?.updateSetting("debugLog", [type:"bool", value: false])
	app?.updateSetting("infoLog", [type:"bool", value: true])
	initialize()
}
def updated() { initialize() }
def initialize() {
	logDebug("initialize")
	unschedule()
	if (selectedAddDevices) { addDevices() }
	if (automaticIpPolling == true) { runEvery1Hour(updateDevices) }
}


//	=====	Main Page	=====
def mainPage() {
	logDebug("mainPage")
	setInitialStates()
	return dynamicPage(name:"mainPage",
		title:"<b>bleBox Device Manager</b>",
		uninstall: true,
		install: true) {
		section() {
			href "addDevicesPage",
				title: "<b>Install bleBox Devices</b>",
				description: "Gets device information. Then offers new devices for install.\n" +
							 "<b>(It will take a minute for the next page to load.)</b>"
			href "listDevicesPage",
					title: "<b>List all available bleBox devices and update the IP for installed devices.</b>",
					description: "Lists available devices.\n" +
								 "<b>(It will take a minute for the next page to load.)</b>"
			input ("infoLog", "bool",
				   required: false,
				   submitOnChange: true,
				   title: "Enable Application Info Logging")
			input ("debugLog", "bool",
				   required: false,
				   submitOnChange: true,
				   title: "Enable Application Debug Logging")
			paragraph "<b>Recommendation:  Set Static IP Address in your WiFi router for all bleBox Devices. " +
				"The polling option takes significant system resources while running.</b>"
			input ("automaticIpPolling", "bool",
				   required: false,
				   submitOnChange: true,
				   title: "Start (true) / Stop (false) Hourly IP Polling",
				   description: "Not Recommended.")
		}
	}
}


//	=====	Add Devices	=====
def addDevicesPage() {
	state.devices = [:]
	findDevices(200, "parseDeviceData")
	getAddedData()
    def devices = state.devices
	def newDevices = [:]
	devices.each {
		def isChild = getChildDevice(it.value.dni)
		if (!isChild) {
			newDevices["${it.value.dni}"] = "${it.value.type} ${it.value.label}"
		}
	}
	logDebug("addDevicesPage: newDevices = ${newDevices}")
	return dynamicPage(name:"addDevicesPage",
		title:"<b>Add bleBox Devices to Hubitat</b>",
		install: true) {
	 	section() {
			input ("selectedAddDevices", "enum",
				   required: false,
				   multiple: true,
				   title: "Devices to add (${newDevices.size() ?: 0} available)",
				   description: "Use the dropdown to select devices to add.  Then select 'Done'.",
				   options: newDevices)
		}
	}
}
def parseDeviceData(response) {
	def cmdResponse = parseResponse(response)
	logDebug("parseDeviceData: ${cmdResponse}")
	if (cmdResponse == "error") { return }
	if (cmdResponse.device) { cmdResponse = cmdResponse.device }
	def label = cmdResponse.deviceName
	def dni = cmdResponse.id.toUpperCase()
	def type = cmdResponse.type
	def ip = convertHexToIP(response.ip)
	def typeData
	if (type == "gateBox" && cmdResponse.hv.length() > 5) {
		if (cmdResponse.hv.substring(0,7) == "doorBox") { type = "doorBox" }
	} else if (type == "wLightBox") {
		type = "wLightBox RGBW"
	}
	def devData = [:]
	devData["dni"] = dni
	devData["ip"] = ip
	devData["label"] = label
	devData["type"] = type
	state.devices << ["${dni}" : devData]
	def isChild = getChildDevice(dni)
	if (isChild) {
		isChild.updateDataValue("deviceIP", ip)
	}
}
def getAddedData() {
	logDebug("getAddedData")
	devices = state.devices
	devices.each {
		if (it.value.type == "shutterBox") {
			state.tempDni = it.value.dni
			sendGetCmd(it.value.ip, """/api/settings/state""", "parseShutterData")
			pauseExecution(500)
		} else if (it.value.type == "switchBoxD") {
			sendGetCmd(it.value.ip, """/api/relay/state""", "parseRelayData")
			pauseExecution(500)
		} else if (it.value.type == "wLightBox RGBW") {
			sendGetCmd(it.value.ip, """/api/rgbw/state""", "parseRgbwData")
			pauseExecution(500)
		} else if (it.value.type == "dimmerBox") {
			setGetCmd(it.vvalue.ip, "/api/dimmer/state", "parseDimmerData")
		}
	}
	pauseExecution(2000)
}
def parseShutterData(response) {
	def cmdResponse = parseResponse(response)
	logDebug("parseShutterData: ${cmdResponse}")
	if (cmdResponse == "error") { return }
	def controlType = cmdResponse.settings.shutter.controlType
	if (controlType == 3) {
		def devIp = convertHexToIP(response.ip)
		def device = state.devices.find {it.value.ip == devIp }
		device.value << [type: "shutterBox Tilt"]
	}
}
def parseRelayData(response) {
	def cmdResponse = parseResponse(response)
	logDebug("parseRelayData: ${cmdResponse}")
	if (cmdResponse == "error") { return }
	def relays = cmdResponse.relays
	def devIp = convertHexToIP(response.ip)
	def device = state.devices.find { it.value.ip == devIp }
	def dni = device.value.dni
	device.value << [dni:"${dni}-0", label:relays[0].name, relayNumber:"0"]
	pauseExecution(2000)
	def relay2Data = ["dni": "${dni}-1",
					  "ip": device.value.ip,
					  "type": device.value.type,
					  "label": relays[1].name,
					  "relayNumber": "1"]
	state.devices << ["${dni}-1" : relay2Data]
}
def parseRgbwData(response) {
	def cmdResponse = parseResponse(response)
	logDebug("parseRgbwData: ${cmdResponse}")
	if (cmdResponse == "error") { return }
	if (cmdResponse.rgbw.colorMode == 3) {
		def devIp = convertHexToIP(response.ip)
		def device = state.devices.find {it.value.ip == devIp }
		device.value << [type: "wLightBox Mono"]
	}
}
def parseDimmerData(response) {
	def cmdResponse = parseResponse(response)
	logDebug("parseDimmerData: ${cmdResponse}")
	if (cmdResponse == "error") { return }
	if (cmdResponse.dimmer.loadType == 2) {
		def devIp = convertHexToIP(response.ip)
		def device = state.devices.find {it.value.ip == devIp }
		device.value << [type: "dimmerBox NoDim"]
	}
}
def addDevices() {
	logDebug("addDevices:  Devices = ${state.devices}")
	try { hub = location.hubs[0] }
	catch (error) { 
		logWarn("Hub not detected.  You must have a hub to install this app.")
		return
	}
	def hubId = hub.id
	selectedAddDevices.each { dni ->
		def isChild = getChildDevice(dni)
		if (!isChild) {
			def device = state.devices.find { it.value.dni == dni }
			def deviceData = [:]
			deviceData["applicationVersion"] = appVersion()
			deviceData["deviceIP"] = device.value.ip
			if (device.value.relayNumber) { deviceData["relayNumber"] = device.value.relayNumber }
			try {
				addChildDevice(
					"davegut",
					"bleBox ${device.value.type}",
					device.value.dni,
					hubId, [
						"label" : device.value.label,
						"name" : device.value.type,
						"data" : deviceData
					]
				)
			} catch (error) {
				logWarn("Failed to install ${device.value.label}.  Driver bleBox ${device.value.type} most likely not installed.")
			}
		}
	}
}


//	=====	Update Device IPs	=====
def listDevicesPage() {
	logDebug("updateIpsPage")
	state.deviceIps = [:]
	findDevices(200, "parseIpData")
	def deviceIps = state.deviceIps
	def foundDevices = "<b>Found Devices (Installed / DNI / IP / Alias):</b>"
	def count = 1
	deviceIps.each {
		def installed = false
		if (getChildDevice(it.value.dni)) { installed = true }
		foundDevices += "\n${count}:\t${installed}\t${it.value.dni}\t${it.value.ip}\t${it.value.label}"
		count += 1
	}
	return dynamicPage(name:"listDevicesPage",
		title:"<b>Available bleBox Devices on your LAN</b>",
		install: false) {
	 	section() {
			paragraph "The appliation has searched and found the below devices. If any are " +
				"missing, there may be a problem with the device.\n\n${foundDevices}\n\n" +
				"<b>RECOMMENDATION: Set Static IP Address in your WiFi router for bleBox Devices.</b>"
		}
	}
}
def parseIpData(response) {
	def cmdResponse = parseResponse(response)
	if (cmdResponse == "commsError") { return }
	logDebug("parseIpData: ${cmdResponse}")
	def device = cmdResponse
	if (device.device) { device = device.device }
	def label = device.deviceName
	def ip = convertHexToIP(response.ip)
	def dni = device.id.toUpperCase()
	if (device.type == "switchBoxD") {
		addIpData("${dni}-0", ip, label)
		addIpData("${dni}-1", ip, label)
		return
	}
	addIpData(dni, ip, label)
}
def addIpData(dni, ip, label) {
	logDebug("addData: ${dni} / ${ip} / ${label}")
	def device = [:]
	def deviceIps = state.deviceIps
	device["dni"] = dni
	device["ip"] = ip
	device["label"] = label
	deviceIps << ["${dni}" : device]
	def isChild = getChildDevice(dni)
	if (isChild) {
		isChild.updateDataValue("deviceIP", ip)
	}
}


//	===== Recurring IP Check =====
def updateDevices() {
	logDebug("UpdateDevices: ${state.devices}")
	state.missingDevice = false
	def devices = state.deviceIps
	if (deviceIps == [:]) {
		findDevices(1000, parseIpData)
		return
	}
	devices.each {
		if (state.missingDevice == true) { return }
		def deviceIP = it.value.ip
		runIn(2, setMissing)
		sendGetCmd(deviceIP, "/api/device/state", checkValid)
		pauseExecution(2100)
	}
	if (state.missingDevice == true) {
		state.deviceIps= [:]
		findDevices(1000, parseIpData)
		state.missingDevices == false
	}
}
def checkValid() {
	def cmdResponse = parseResponse(response)
	if (cmdResponse == "commsError") { return }
	logDebug("parseIpData: ${cmdResponse}")
	unschedule("setMissing")
}
def setMissing() { state.missingDevice = true }


//	=====	Device Communications	=====
def findDevices(pollInterval, action) {
	logDebug("findDevices: ${pollInterval} / ${action}")
	def hub
	try { hub = location.hubs[0] }
	catch (error) { 
		logWarn "Hub not detected.  You must have a hub to install this app."
		return
	}
	def hubIpArray = hub.localIP.split('\\.')
	def networkPrefix = [hubIpArray[0],hubIpArray[1],hubIpArray[2]].join(".")
	logInfo("findDevices: IP Segment = ${networkPrefix}")
	for(int i = 2; i < 254; i++) {
		def deviceIP = "${networkPrefix}.${i.toString()}"
		sendGetCmd(deviceIP, "/api/device/state", action)
		pauseExecution(pollInterval)
	}
	pauseExecution(3000)
}
private sendGetCmd(ip, command, action){
	logDebug("sendGetCmd: ${ip} / ${command} / ${action}")
	sendHubCommand(new hubitat.device.HubAction("GET ${command} HTTP/1.1\r\nHost: ${ip}\r\n\r\n",
												hubitat.device.Protocol.LAN, null, [callback: action, timeout: 2]))
}
def parseResponse(response) {
	def cmdResponse
	if(response.status != 200) {
		cmdResponse = "error"
		logWarn("parseResponse: Command generated an error return: ip = ${convertHexToIP(response.ip)}, ${response.status}")
	} else if (response.body == null){
		logWarn("parseResponse: ip = ${convertHexToIP(response.ip)}, no data in command response.")
		cmdResponse = "error"
	} else {
		def jsonSlurper = new groovy.json.JsonSlurper()
		cmdResponse = jsonSlurper.parseText(response.body)
	}
	return cmdResponse
}


//	=====	Utility methods	=====
def uninstalled() {
    getAllChildDevices().each { 
        deleteChildDevice(it.deviceNetworkId)
    }
}
private String convertHexToIP(hex) {
	[convertHexToInt(hex[0..1]),convertHexToInt(hex[2..3]),convertHexToInt(hex[4..5]),convertHexToInt(hex[6..7])].join(".")
}
private Integer convertHexToInt(hex) { Integer.parseInt(hex,16) }
def logDebug(msg){
	if(debugLog == true) { log.debug "<b>${appVersion()}</b> ${msg}" }
}
def logInfo(msg){
	if(infoLog == true) { log.info "<b>${appVersion()}</b> ${msg}" }
}
def logWarn(msg) { log.warn "<b>${appVersion()}</b> ${msg}" }

//	end-of-file