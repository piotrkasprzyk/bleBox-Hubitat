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
08.15.19	1.1.01. Integrated design notes at bottom and updated implementation per notes.
*/
//	===== Definitions, Installation and Updates =====
def driverVer() { return "1.1.01" }
metadata {
	definition (name: "bleBox wLightBox RGBW",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/bleBox-Hubitat/master/Drivers/wLightBox%20RGBW_RGB.groovy"
			   ) {
		capability "Light"
		capability "Switch"
		capability "Actuator"
		capability "Switch Level"
		capability "Refresh"
		capability "Color Control"
		capability "Color Mode"
		command "setWhiteLevel", ["NUMBER"]
		attribute "whiteLevel", "num"
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
 	sendEvent(name: "colorMode", value: "RGB")
	state.savedWhite = "00"
	state.savedRGB = "000000"
	state.savedRGBW = "00000000"
	runIn(2, updated)
}
def updated() {
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
	logInfo("Default fade speed set to ${state.defFadeSpeed}")
	updateDataValue("driverVersion", driverVer())

	logInfo("Debug logging is: ${debug}.")
	logInfo("Description text logging is ${descriptionText}.")
	logInfo("Refresh interval set for every ${refreshInterval} minute(s).")
	refresh()
}


//	===== Commands and Parse Returns =====
def on() {
	logDebug("on")
	def rgbw = state.savedRGBW
	sendPostCmd("/api/rgbw/set",
				"""{"rgbw":{"desiredColor":"${rgbw}","fadeSpeed":${state.defFadeSpeed}}}""",
				"commandParse")
}
def off() {
	logDebug("off")
	sendGetCmd("/s/00000000", "commandParse")
	sendPostCmd("/api/rgbw/set",
				"""{"rgbw":{"desiredColor":"00000000","fadeSpeed":${state.defFadeSpeed}}}""",
				"commandParse")
}
def setWhiteLevel(whiteLevel) {
	logDebug("setWhite: ${whiteLevel})")
	def white255 = (whiteLevel * 2.55).toInteger()
	def wHex = hubitat.helper.HexUtils.integerToHexString(white255, 1)
	def rgbw = state.savedRGB + wHex
	sendPostCmd("/api/rgbw/set",
				"""{"rgbw":{"desiredColor":"${rgbw}","fadeSpeed":${state.defFadeSpeed}}}""",
				"commandParse")
}
def setLevel(level, transTime = null) {
	logDebug("setLevel: level = ${level})")
	if (transTime != null) { state.tempFade = getFadeSpeed(transTime) }
	setColor([hue: device.currentValue("hue"),
			  saturation: device.currentValue("saturation"),
			  level: level.toInteger()])
}

def setHue(hue) {
	logDebug("setHue:  hue = ${hue}")
	setColor([hue: hue.toInteger(),
			  saturation: device.currentValue("saturation"),
			  level: device.currentValue("level")])
}
def setSaturation(saturation) {
	logDebug("setSaturation: saturation = ${saturation}")
	setColor([hue: device.currentValue("hue"),
			  saturation: saturation.toInteger(),
			  level: device.currentValue("level")])
}
def setColor(color) {
	logDebug("setColor:  color = ${color}")
	def hue = color.hue
	if (hue == 0) { hue = 1 }
	def saturation = color.saturation
	if (saturation == 0) { saturation = hue }
	def level = color.level
	if (level == 0) { level = hue }
	def rgbData = hubitat.helper.ColorUtils.hsvToRGB([hue, saturation, level])
	def rgbw = hubitat.helper.HexUtils.integerToHexString(rgbData[0], 1)
	rgbw += hubitat.helper.HexUtils.integerToHexString(rgbData[1], 1)
	rgbw += hubitat.helper.HexUtils.integerToHexString(rgbData[2], 1)
	rgbw += state.savedWhite
	def fadeSpeed = state.defFadeSpeed
	if (state.tempFade != null) {
		fadeSpeed = state.tempFade
		state.tempFade = null
	}
	sendPostCmd("/api/rgbw/set",
				"""{"rgbw":{"desiredColor":"${rgbw}","fadeSpeed":${fadeSpeed}}}""",
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
	logDebug("refresh.")
	sendGetCmd("/api/rgbw/state", "commandParse")
}
def commandParse(response) {
	if(response.status != 200 || response.body == null) {
		logWarn("parseInput: Command generated an error return: ${response.status} / ${response.body}")
		return
	}
	def jsonSlurper = new groovy.json.JsonSlurper()
	def cmdResponse = jsonSlurper.parseText(response.body)
	logDebug("commandParse: ${cmdResponse}")
	def hexDesired = cmdResponse.rgbw.desiredColor.toUpperCase()
	if (hexDesired == state.savedRGBW && device.currentValue("switch") == "on") {
		return
	} else if (hexDesired == "00000000") {
		sendEvent(name: "switch", value: "off")
		sendEvent(name: "hue", value: 0)
		sendEvent(name: "saturation", value: 0)
		sendEvent(name: "level", value: 0)
		def color = ["hue": 0, "saturation": 0, "level": 0]
		sendEvent(name: "color", value: color)
		sendEvent(name: "RGB", value: "000000")
		sendEvent(name: "whiteLevel", value: whiteLevel)
	} else {
		sendEvent(name: "switch", value: "on")
		state.savedRGBW = hexDesired
	}
	
	state.savedRGB = hexDesired[0..5]
	sendEvent(name: "RGB", value: hexDesired[0..5])
	def red255 = Integer.parseInt(hexDesired[0..1],16)
	def green255 = Integer.parseInt(hexDesired[2..3],16)
	def blue255 = Integer.parseInt(hexDesired[4..5],16)
	
	def hsvData = hubitat.helper.ColorUtils.rgbToHSV([red255, green255, blue255])
	def hue = (0.5 + hsvData[0]).toInteger()
	def saturation = (0.5 + hsvData[1]).toInteger()
	def level = (0.5 + hsvData[2]).toInteger()
	sendEvent(name: "hue", value: hue)
	sendEvent(name: "saturation", value: saturation)
	sendEvent(name: "level", value: level)
	def color = ["hue": hue, "saturation": saturation, "level": level]
	sendEvent(name: "color", value: color)
	
	state.savedWhite = hexDesired[6..7]
	def whiteLevel = Integer.parseInt(hexDesired[6..7],16)
	whiteLevel = (0.5 + whiteLevel/2.55).toInteger()
	sendEvent(name: "whiteLevel", value: whiteLevel)

	setColorData(hue)
}
def setColorData(hue){
	logDebug("setRgbData: hue = ${hue}")
    def colorName
	switch (hue){
		case 0..4: colorName = "Red"
			break
		case 5..12: colorName = "Orange"
			break
		case 13..20: colorName = "Yellow"
			break
		case 21..29: colorName = "Chartreuse"
			break
		case 30..37: colorName = "Green"
			break
		case 38..45: colorName = "Spring"
			break
		case 46..54: colorName = "Cyan"
			break
		case 55..62: colorName = "Azure"
			break
		case 63..255: colorName = "Blue"
			break
		case 256..70: colorName = "Violet"
			break
		case 71..87: colorName = "Magenta"
			break
		case 88..95: colorName = "Rose"
			break
		case 96..100: colorName = "Red"
			break
		deafult: colorName = "Unknown"
	}
	logDebug("setRgbData: Color is ${colorName}.")
	sendEvent(name: "colorName", value: colorName)
}


//	===== Name Sync Capability =====
def syncName() {
	logDebug("syncName. Synchronizing device name and label with master = ${nameSync}")
	if (nameSync == "hub") {
		sendPostCmd("/api/settings/set",
					"""{"settings":{"deviceName":"${device.label}","statusLed":{"enabled":0}}}""",
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
	def deviceName = cmdResponse.deviceName
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
wLightBox RGBW
----- Device Installation Assumptions
	wLightBox color mode set to "RGBW" or "RGB"
----- Capability and Commands
	Capability: Switch and Light:
		Command: on():	Turns on the device with the level at the last used level.
		Command: off():	Turns off the device.
		Attribute:	switch:	Current value of the switch state (on or off).
	Capability Switch Level
		Command: level from 0 to 100, representing 0 to 255 brightness in the currentBrightness
				 value.
	Capability:	Actuator.  Used for rule machine (and others) to access switch commands.
	Capability: Refresh
		Command: refresh().  Forces reading of dimmerBox state data.
	Capability: Color Control.  Provides color control commands and attributes
		Commands: Set Color, Set Hue, Set Saturation
		Attributes: RGB (hex), color (map), colorName, hue, saturation
	Command: Set White Level. Sets the white channel (channel 4) level.
		Attribute: whiteLevel
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