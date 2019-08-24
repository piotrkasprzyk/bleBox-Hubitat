/*
===== Blebox Hubitat Integration Driver

	Copyright 2019, Dave Gutheinz

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this  file except in compliance with the
License. You may obtain a copy of the License at: http://www.apache.org/licenses/LICENSE-2.0.
Unless required by applicable law or agreed to in writing,software distributed under the License is distributed on an 
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific 
language governing permissions and limitations under the License.

DISCLAIMER: The author of this integration is not associated with blebox.  This code uses the blebox
open API documentation for development and is intended for integration into the Hubitat Environment.

===== Hiatory =====
08.14.19	Various edits.
08.15.19	1.1.01. Integrated design notes at bottom and updated implementation per notes.
*/
//	===== Definitions, Installation and Updates =====
def driverVer() { return "1.1.01" }
metadata {
	definition (name: "bleBox wLightBoxS",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/bleBox-Hubitat/master/Drivers/wLightBoxS.groovy"
			   ) {
		capability "Light"
		capability "Switch"
		capability "Switch Level"
		capability "Actuator"
		capability "Refresh"
	}
	preferences {
		if (!getDataValue("applicationVersion")) {
			input ("device_IP", "text", title: "Device IP (Current = ${getDataValue("deviceIP")})")
		}
		input ("transTime", "num", title: "Default Transition time (0 - 60 seconds maximum)", defaultValue: 1)
		input ("refreshInterval", "enum", title: "Device Refresh Interval (minutes)", 
			   options: ["1", "5", "15", "30"], defaultValue: "30")
		input ("nameSync", "enum", title: "Synchronize Names", defaultValue: "none",
			   options: ["none": "Don't synchronize",
						 "device" : "bleBox device name master", 
						 "hub" : "Hubitat label master"])
		input ("debug", "bool", title: "Enable debug logging", defaultValue: false)
		input ("descriptionText", "bool", title: "Enable description text logging", defaultValue: true)
	}
}
def installed() {
	logInfo("Installing...")
	state.savedLevel = "FF"
	runIn(2, updated)
}
def updated() {
	state.notice = "<b>Developmental Version.  This is the pre-Alpha version of this driver.</b>"
	logInfo("Updating...")
	unschedule()

	if (!getDataValue("applicationVersion")) {
		if (!device_IP) {
			logWarn("updated:  deviceIP  is not set.")
			return
		}
		updateDataValue("deviceIP", device_IP)
		logInfo("Device IP set to ${getDataValue("deviceIP")}")
	}

	switch(refreshInterval) {
		case "1" : runEvery1Minute(refresh); break
		case "5" : runEvery5Minutes(refresh); break
		case "15" : runEvery15Minutes(refresh); break
		default: runEvery30Minutes(refresh)
	}

	if (nameSync == "device" || nameSync == "hub") { syncName() }
	state.defFadeSpeed = getFadeSpeed(transTime)
	updateDataValue("driverVersion", driverVer())

	logInfo("Debug logging is: ${debug}.")
	logInfo("Description text logging is ${descriptionText}.")
	logInfo("Refresh interval set for every ${refreshInterval} minute(s).")
	
	refresh()
}


//	===== Commands and Parse Returns =====
def on() {
	logDebug("on")
	sendPostCmd("/api/light/set",
				"""{"light":{"desiredColor":"${state.savedLevel}","fadeSpeed":${state.defFadeSpeed}}}""",
				"commandParse")
}
def off() {
	logDebug("off")
	sendPostCmd("/api/light/set",
				"""{"light":{"desiredColor":"00","fadeSpeed":${state.defFadeSpeed}}}""",
				"commandParse")
}
def setLevel(level, transitionTime = null) {
	def fadeSpeed = state.defFadeSpeed
	if (transitionTime != null) { fadeSpeed = getFadeSpeed(transitionTime) }
	logDebug("setLevel: level = ${level} // ${fadeSpeed}")
	level = (2.55 * level + 0.5).toInteger()
	def hexLevel = hubitat.helper.HexUtils.integerToHexString(level, 1)
	state.savedLevel = "${hexLevel}"
	sendPostCmd("/api/light/set",
				"""{"light":{"desiredColor":"${hexLevel}","fadeSpeed":${fadeSpeed}}}""",
				"commandParse")
}
def getFadeSpeed(transitionTime) {
	logDebug("getFadeSpeed: ${transitionTime}")
	def timeIndex = (10* transitionTime.toFloat()).toInteger()
	def fadeSpeed
	switch (timeIndex) {
		case 0: fadeSpeed = 255; break
		case 1..7 :		fadeSpeed = 234; break
		case 8..15 :	fadeSpeed = 229; break
		case 16..25 :	fadeSpeed = 219; break
		case 26..35 : 	fadeSpeed = 215; break
		case 36..45 : 	fadeSpeed = 213; break
		case 46..55 : 	fadeSpeed = 212; break
		case 56..65 :	fadeSpeed = 211; break
		case 66..90 : 	fadeSpeed = 209; break
		case 91..125 : 	fadeSpeed = 207; break
		case 126..175 : fadeSpeed = 202; break
		case 176..225 : fadeSpeed = 199; break
		case 226..275 : fadeSpeed = 197; break
		case 276..350 :	fadeSpeed = 194; break
		case 351..450 : fadeSpeed = 189; break
		case 451..550 : fadeSpeed = 185; break
		default: fadeSpeed = 179
	}
	return fadeSpeed
}

def refresh() {
	logDebug("refresh")
	sendGetCmd("/api/light/state", "commandParse")
}
def commandParse(response) {
	if(response.status != 200 || response.body == null) {
		logWarn("parseInput: Command generated an error return: ${response.status} / ${response.body}")
		return
	}
	def jsonSlurper = new groovy.json.JsonSlurper()
	def cmdResponse = jsonSlurper.parseText(response.body)
	logDebug("commandParse: response = ${cmdResponse}")
	def hexLevel = cmdResponse.light.desiredColor.toUpperCase()
	if (hexLevel == "00") {
		sendEvent(name: "switch", value: "off")
		sendEvent(name: "level", value: 0)
		logInfo "commandParse: switch = off"
	} else {
		sendEvent(name: "switch", value: "on")
		def brightness = hubitat.helper.HexUtils.hexStringToInt(hexLevel)
		def level = (0.5 + brightness/ 2.55).toInteger()
		sendEvent(name: "level", value: level)
		logInfo "commandParse: switch = on, level = ${level}"
	}
}


//	===== Name Sync Capability =====
def syncName() {
	logDebug("syncName. Synchronizing device name and label with master = ${nameSync}")
	if (nameSync == "hub") {
		sendPostCmd("/api/settings/set",
					"""{"settings":{"deviceName":"${device.label}"}}""",
					"nameSyncHub")
	} else if (nameSync == "device") {
		sendGetCmd("/api/device/state", "nameSyncDevice")
	}
}
def nameSyncHub(response) {
	if(response.status != 200 || response.body == null) {
		logWarn("parseInput: Command generated an error return: ${response.status} / ${response.body}")
		return
	}
	def jsonSlurper = new groovy.json.JsonSlurper()
	def cmdResponse = jsonSlurper.parseText(response.body)
	logDebug("nameSyncHub: ${cmdResponse}")
	logInfo("Setting bleBox device label to that of the Hubitat device.")
}
def nameSyncDevice(response) {
	if(response.status != 200 || response.body == null) {
		logWarn("parseInput: Command generated an error return: ${response.status} / ${response.body}")
		return
	}
	def jsonSlurper = new groovy.json.JsonSlurper()
	def cmdResponse = jsonSlurper.parseText(response.body)
	logDebug("nameSyncDevice: ${cmdResponse}")
	def deviceName = cmdResponse.device.deviceName
	device.setLabel(deviceName)
	logInfo("Hubit name for device changed to ${deviceName}.")
}


//	===== Communications =====
private sendGetCmd(command, action){
	logDebug("sendCmd: command = ${command} // device IP = ${getDataValue("deviceIP")}")
	sendHubCommand(new hubitat.device.HubAction("GET ${command} HTTP/1.1\r\nHost: ${getDataValue("deviceIP")}\r\n\r\n",
				   hubitat.device.Protocol.LAN, null,[callback: action]))
}
private sendPostCmd(command, body, action){
	logDebug("sendPostCmd: command = ${command} // body = ${body}")
	def parameters = [ method: "POST",
					  path: command,
					  protocol: "hubitat.device.Protocol.LAN",
					  body: body,
					  headers: [
						  Host: getDataValue("deviceIP")
					  ]]
	sendHubCommand(new hubitat.device.HubAction(parameters, null, [callback: action]))
}


//	===== Utility Methods =====
def logInfo(msg) {
	if (descriptionText == true) { log.info "<b>${device.label} ${driverVer()}</b> ${msg}" }
}
def logDebug(msg){
	if(debug == true) { log.debug "<b>${device.label} ${driverVer()}</b> ${msg}" }
}
def logWarn(msg){ log.warn "<b>${device.label} ${driverVer()}</b> ${msg}" }

/*
===== DESIGN NOTES =====
wLightBoxS
----- Device Installation Assumptions
	None.
----- Capability and Commands
	Capability: Switch and Light:
		Command: on():	Turns on the device with the level at the last used level.
		Command: off():	Turns off the device.
		Attribute:	switch:	Current value of the switch state (on or off).
	Capability Switch Level
		Command: level from 0 to 100, representing 0 to 255 brightness in the currentBrightness
				 value in the dimmerBox.
	Capability:	Actuator.  Used for rule machine (and others) to access switch commands.
	Capability: Refresh
		Command: refresh().  Forces reading of dimmerBox state data.
----- Preferences
	device_IP:  Used in manual installation of driver only.
	transTime: The time (in seconds) for the device to transition from 0 to full brightness.
	refreshInterval:  frequency to refresh the attribute 'contact'.  Sets to a default
					  of 30 minute.
	fastPoll: provides a fast polling interval (seconds).  Note that this uses Hub
			  resources and can impact a Hub's perceived performance if near limits.
	nameSync: Coordinates the naming between the device and the Hubitat interface.
			  'Hubitat Label Master' - changes name in bleBox device
			  'bleBox device name master' - changes Hubitat label to name in bleBox device.
*/

//	end-of-file