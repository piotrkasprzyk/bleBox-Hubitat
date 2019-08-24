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
*/
//	===== Definitions, Installation and Updates =====
def driverVer() { return "1.1.01" }
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
	logInfo("Refresh interval set for every 5 minutes.")

	if (nameSync == "device" || nameSync == "hub") { syncName() }
	runIn(1, setLed)
	updateDataValue("driverVersion", driverVer())

	logInfo("Debug logging is: ${debug}.")
	logInfo("Description text logging is ${descriptionText}.")
	refresh()
}


//	===== Commands and updating state =====
def push() {
	logDebug("push.")
	sendGetCmd("/api/air/kick", "kickParse")
}
def kickParse(response) {
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
	if(response.status != 200 || response.body == null) {
		logWarn("parseInput: Command generated an error return: ${response.status} / ${response.body}")
		return
	}
	def jsonSlurper = new groovy.json.JsonSlurper()
	def cmdResponse = jsonSlurper.parseText(response.body)
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
	if(response.status != 200 || response.body == null) {
		logWarn("parseInput: Command generated an error return: ${response.status} / ${response.body}")
		return
	}
	def jsonSlurper = new groovy.json.JsonSlurper()
	def cmdResponse = jsonSlurper.parseText(response.body)
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
airSensor
----- Device Installation Assumptions
	None.
----- Capability and Commands
	Capability: Momentary
		Command: push().  Causes a Kick to be sent to the air sensor (forcing a measurement).
				 After commanded, the sensor is read after 60 seconds.
	Capability: Refresh.  Not implemented.  Data is refreshed every 5 minutes to sync with
				the sensor's 10 minute automatic measurement.
	Attributes:
		PM_N_Measurement: in micrograms/m3.  N is 1, 2_5, 10.
		PM_N_Trend: up, down, even, or no data from airSensor.  N is 1, 2_5, 10.
		PM_N_Quality: Calculated of measurement/standard.  N is 2_5, 10.  Standards are 25
					  for PM2_5 and 50 for pm10.
		airQuality: max of the index reported from airSensor for PM2.5 and PM10.  Values:
							0 - no data
							1 - Very Good
							2 - Good
							3 - Moderate
							4 - Sufficient
							5 - Bad
							6 - Very Bad
		kickActive: identifies if a forced measurement is in-process.
----- Preferences
	device_IP:  Used in manual installation of driver only.
	statusLed: a value of false turns off the status LED, true turns on the status LED.
	nameSync: Coordinates the naming between the device and the Hubitat interface.
			  'Hubitat Label Master' - changes name in bleBox device
			  'bleBox device name master' - changes Hubitat label to name in bleBox device.
----- Operational Concept in code
	a.	The airSensor automatically does all measurements.
	b.	On 'push', a kick command is sent.  One minute later, the data values are updated.
	c.	A refresh is run every 5 minutes to collect current data (updated every 10 minutes
		by the airSensor.
*/
//	end-of-file