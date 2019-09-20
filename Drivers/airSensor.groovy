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
08.14.19	Added Capability Sensor to provide hook for applications.
08.15.19	1.1.01. Integrated design notes at bottom and updated implementation per notes.
09.20.19	1.2.01.	Added link to Application that will check/update IPs if the communications fail.
*/
//	===== Definitions, Installation and Updates =====
def driverVer() { return "1.2.01" }
metadata {
	definition (name: "bleBox airSensor",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/bleBox-Hubitat/master/Drivers/airSensor.groovy"
			   ) {
		capability "Momentary"
		capability "Sensor"
		attribute "PM_1_Measurement", "string"
		attribute "PM_1_Trend", "string"
		attribute "PM_2_5_Measurement", "string"
		attribute "PM_2_5_Trend", "string"
		attribute "PM_2_5_Quality", "number"
		attribute "PM_10_Measurement", "string"
		attribute "PM_10_Trend", "string"
		attribute "PM_10_Quality", "number"
		attribute "airQuality", "string"
		attribute "kickActive", "bool"
		attribute "commsError", "bool"
	}
	preferences {
		if (!getDataValue("applicationVersion")) {
			input ("device_IP", "text", title: "Device IP (Current = ${getDataValue("deviceIP")})")
		}
		input ("nameSync", "enum", title: "Synchronize Names", defaultValue: "none",
			   options: ["none": "Don't synchronize",
						 "device" : "bleBox device name master", 
						 "hub" : "Hubitat label master"])
		input ("statusLed", "bool", title: "Enable the Status LED", defaultValue: true)
//		input ("debug", "bool", title: "Enable debug logging", defaultValue: false)
		input ("debug", "bool", title: "Enable debug logging", defaultValue: false)
		input ("descriptionText", "bool", title: "Enable description text logging", defaultValue: true)
	}
}

def installed() {
	logInfo("Installing...")
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

	runEvery5Minutes(refresh)
	runIn(1, setLed)
	state.errorCount = 0
	updateDataValue("driverVersion", driverVer())

	logInfo("Refresh interval set for every 5 minutes.")
	logInfo("Debug logging is: ${debug}.")
	logInfo("Description text logging is ${descriptionText}.")

	if (nameSync == "device" || nameSync == "hub") { syncName() }
	refresh()
}


//	===== Commands and updating state =====
def push() {
	logDebug("push.")
	sendGetCmd("/api/air/kick", "kickParse")
}

def kickParse(response) {
	def cmdResponse = parseInput(response)
	logDebug("kickResponse.  Measurement has started and will take about 1 minute for results to show.")
	sendEvent(name: "kickActive", value: true)
	runIn(60, postKick)
}

def postKick() {
	logDebug("postKick.  Measuring quality post Kick.")
	sendEvent(name: "kickActive", value: false)
	refresh()
}

def refresh() {
	logDebug("refesh.")
	sendGetCmd("/api/air/state", "commandParse")
}

def commandParse(response) {
	def cmdResponse = parseInput(response)
	logDebug("commandParse: cmdResp = ${cmdResponse}")
	//	=====	Capture PM_1 Data
	def pm1Data = cmdResponse.air.sensors.find{ it.type == "pm1" }
	def pm1Value = pm1Data.value
	//	=====	Capture PM_2.5 Data
	def pm2_5Data = cmdResponse.air.sensors.find{ it.type == "pm2.5" }
	def pm25Value = pm2_5Data.value
	def pm25Qual = pm2_5Data.qualityLevel
	//	=====	Capture PM_10 Data
	def pm10Data = cmdResponse.air.sensors.find{ it.type == "pm10" }
	def pm10Value = pm10Data.value
	def pm10Qual = pm10Data.qualityLevel
	
//	Simulated data for test
//	pm1Value = 33
//	pm25Value = 50
//	pm25Qual = 6
//	pm10Value = 20
//	pm10Qual = 2	
	
	sendEvent(name: "PM_1_Measurement", value: pm1Value, unit: "micro-g/m3")
	sendEvent(name: "PM_1_Trend", value: "${getTrendText(pm1Data.trend)}")
	sendEvent(name: "PM_2_5_Quality", value: (4 * pm25Value).toInteger())
	sendEvent(name: "PM_2_5_Measurement", value: pm25Value, unit: "micro-g/m3")
	sendEvent(name: "PM_2_5_Trend", value: "${getTrendText(pm2_5Data.trend)}")
	sendEvent(name: "PM_10_Quality", value: (2 * pm10Value).toInteger())
	sendEvent(name: "PM_10_Measurement", value: pm10Value, unit: "micro-g/m3")
	sendEvent(name: "PM_10_Trend", value: "${getTrendText(pm10Data.trend)}")
	def airQual = Math.max(pm25Qual, pm10Qual)
	switch(airQual) {
		case 1:
		sendEvent(name: "airQuality", value: "Very Good")
		break
		case 2:
		sendEvent(name: "airQuality", value: "Good")
		break
		case 3:
		sendEvent(name: "airQuality", value: "Moderate")
		break
		case 4:
		sendEvent(name: "airQuality", value: "Sufficient")
		break
		case 5:
		sendEvent(name: "airQuality", value: "Bad")
		break
		case 6:
		sendEvent(name: "airQuality", value: "Very Bad")
		break
		default:
		sendEvent(name: "airQuality", value: "not available")
	}
	logInfo("refreshParse: Air Quality Data, Index and Category Updated")
}

def getTrendText(trend) {
	def trendText
	switch(trend) {
		case 1:
			trendText = "Even"
			break
		case 2:
			trendText = "Down"
			break
		case 3:
			trendText = "Up"
			break
		default:
			trendText = "no data"
	}
	return trendText
}


//	===== Set Status LED =====
def setLed() {
	logDebug("setLed")
	def enable = 0
	if (statusLed == true) { enable = 1 }
	sendPostCmd("/api/settings/set",
				"""{"settings":{"statusLed":{"enabled":${enable}}}}""",
				"ledStatusParse")
}
def ledStatusParse(response) {
	def cmdResponse = parseInput(response)
	state.statusLed = cmdResponse.settings.statusLed.enabled
	logDebug("ledStatusParse: ${cmdResponse}")
}


//	===== Name Sync Capability =====
def syncName() {
	logDebug("syncName: Synchronizing device name and label with master = ${nameSync}")
	if (nameSync == "hub") {
		sendPostCmd("/api/settings/set",
					"""{"settings":{"deviceName":"${device.label}","statusLed":{"enabled":1}}}""",
					"nameSyncHub")
	} else if (nameSync == "device") {
		sendGetCmd("/api/device/state", "nameSyncDevice")
	}
}
def nameSyncHub(response) {
	def cmdResponse = parseInput(response)
	logDebug("nameSyncHub: ${cmdResponse}")
	logInfo("Setting bleBox device label to that of the Hubitat device.")
}
def nameSyncDevice(response) {
	def cmdResponse = parseInput(response)
	logDebug("nameSyncDevice: ${cmdResponse}")
	def deviceName = cmdResponse.deviceName
	device.setLabel(deviceName)
	logInfo("Hubit name for device changed to ${deviceName}.")
}


//	===== Communications =====
private sendGetCmd(command, action){
	logDebug("sendGetCmd: ${command} / ${action} / ${getDataValue("deviceIP")}")
	state.lastCommand = [type: "get", command: "${command}", body: "n/a", action: "${action}"]
	runIn(3, setCommsError)
	sendHubCommand(new hubitat.device.HubAction("GET ${command} HTTP/1.1\r\nHost: ${getDataValue("deviceIP")}\r\n\r\n",
				   hubitat.device.Protocol.LAN, null,[callback: action]))
}
private sendPostCmd(command, body, action){
	logDebug("sendGetCmd: ${command} / ${body} / ${action} / ${getDataValue("deviceIP")}")
	state.lastCommand = [type: "post", command: "${command}", body: "${body}", action: "${action}"]
	runIn(3, setCommsError)
	def parameters = [ method: "POST",
					  path: command,
					  protocol: "hubitat.device.Protocol.LAN",
					  body: body,
					  headers: [
						  Host: getDataValue("deviceIP")
					  ]]
	sendHubCommand(new hubitat.device.HubAction(parameters, null, [callback: action]))
}
def parseInput(response) {
	unschedule(setCommsError)
	state.errorCount = 0
	sendEvent(name: "commsError", value: false)
	if(response.status != 200 || response.body == null) {
		logWarn("parseInput: Command generated an error return: ${response.status} / ${response.body}")
		return
	}
	try {
		def jsonSlurper = new groovy.json.JsonSlurper()
		return jsonSlurper.parseText(response.body)
	} catch (error) {
		logWarn "CommsError: ${error}."
	}
}
def setCommsError() {
	logDebug("setCommsError")
	if (state.errorCount < 3) {
		state.errorCount+= 1
		repeatCommand()
		logWarn("Attempt ${state.errorCount} to recover communications")
	} else if (state.errorCount == 3) {
		state.errorCount += 1
		if (!getDataValue("applicationVersion")) {
			logWarn("setCommsError: Parent commanded to poll for devices to correct error.")
			parent.updateDeviceIps()
			runIn(90, repeatCommand)
		}
	} else {
		sendEvent(name: "commsError", value: true)
		logWarn "setCommsError: No response from device.  Refresh.  If off line " +
				"persists, check IP address of device."
	}
}
def repeatCommand() { 
	logDebug("repeatCommand: ${state.lastCommand}")
	if (state.lastCommand.type == "post") {
		sendPostCmd(state.lastCommand.command, state.lastCommand.body, state.lastCommand.action)
	} else {
		sendGetCmd(state.lastCommand.command, state.lastCommand.action)
	}
}


//	===== Utility Methods =====
def logInfo(msg) {
	if (descriptionText == true) { log.info "<b>${device.label} ${driverVer()}</b> ${msg}" }
}
def logDebug(msg){
	if(debug == true) { log.debug "<b>${device.label} ${driverVer()}</b> ${msg}" }
}
def logWarn(msg){ log.warn "<b>${device.label} ${driverVer()}</b> ${msg}" }

//	end-of-file