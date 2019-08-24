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
	definition (name: "bleBox switchBoxD",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/bleBox-Hubitat/master/Drivers/switchBoxD.groovy"
			   ) {
		capability "Switch"
        capability "Actuator"
		capability "Refresh"
	}
	preferences {
		if (!getDataValue("applicationVersion")) {
			input ("device_IP", "text", title: "Device IP (Current = ${getDataValue("deviceIP")})")
			input ("relay_Number", "enum", title: "Relay Number", options: ["0", "1"])
		}
		input ("refreshInterval", "enum", title: "Device Refresh Interval (minutes)", 
			   options: ["1", "5", "15", "30"], defaultValue: "30")
		input ("fastPoll", "enum",title: "Enable fast polling", 
			   options: ["No", "1", "2", "3", "4", "5", "10", "15"], defaultValue: "No")
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
	runIn(2, updated)
}
def updated() {
	logInfo("Updating...")
	unschedule()
	
	if (!getDataValue("applicationVersion")) {
		if (!device_IP || !relay_Number) {
			logWarn("updated:  Either deviceIP or RelayNo is/are not set.")
			return
		}
		updateDataValue("deviceIP", device_IP)
		updateDataValue("relayNumber", relay_Number)
		logInfo("DeviceIP = ${getDataValue("deviceIP")}, RelayNumber = ${getDataValue("relayNumber")}")
	}

	switch(refreshInterval) {
		case "1" : runEvery1Minute(refresh); break
		case "5" : runEvery5Minutes(refresh); break
		case "15" : runEvery15Minutes(refresh); break
		default: runEvery30Minutes(refresh)
	}

	if (!fastPoll || fastPoll =="No") { state.pollInterval = "0" }
	else { state.pollInterval = fastPoll }

	if (nameSync == "device" || nameSync == "hub") { syncName() }
	updateDataValue("driverVersion", driverVer())

	logInfo("Debug logging is: ${debug}.")
	logInfo("Description text logging is ${descriptionText}.")
	logInfo("Refresh interval set for every ${refreshInterval} minute(s).")
	refresh()
}


//	===== Commands and Refresh with Response =====
def on() {
	logDebug("on")
	sendGetCmd("/s/${getDataValue("relayNumber")}/1", "commandParse")
}
def off() {
	logDebug("off")
	sendGetCmd("/s/${getDataValue("relayNumber")}/0", "commandParse")
}
def refresh() {
	logDebug("refresh")
	sendGetCmd("/api/relay/state", "commandParse")
}
def quickPoll() { sendGetCmd("/api/relay/state", "commandParse") }
def commandParse(response) {
	if(response.status != 200 || response.body == null) {
		logWarn("parseInput: Command generated an error return: ${response.status} / ${response.body}")
		return
	}
	def jsonSlurper = new groovy.json.JsonSlurper()
	def cmdResponse = jsonSlurper.parseText(response.body)
	logDebug("commandParse: response = ${cmdResponse}")

	def relay = getDataValue("relayNumber").toInteger()
	def thisRelay = cmdResponse.relays.find{ it.relay == relay }
	def onOff = "off"
	if (thisRelay.state == 1) { onOff = "on" }
	if (onOff != device.currentValue("switch")) {
		sendEvent(name: "switch", value: onOff)
		logInfo("cmdResponse: switch = off")
	}
	if (state.pollInterval != "0") {
		runIn(state.pollInterval.toInteger(), quickPoll)
	}
}


//	===== Name Sync Capability =====
def syncName() {
	logDebug("syncName. Synchronizing device name and label with master = ${nameSync}")
	if (nameSync == "hub") {
		def relay = getDataValue("relayNumber")
		def notRelay = 1
		if (relay == 1) { notRelay = 0 }
		sendPostCmd("/api/relay/set",
					"""{"relays":[{"relay":${relay},"name":"${device.label}"},{"relay":${notRelay}}]}""",
					"nameSyncHub")
	} else if (nameSync == "device") {
		sendGetCmd("/api/relay/state", "nameSyncDevice")
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
	def deviceName = cmdResponse.relays[getDataValue("relayNumber").toInteger()].name
	log.debug deviceName
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
switchBoxD
----- Device Installation Assumptions
	None.
----- Capability and Commands
	Capability: Switch
		Command: on():	Sends "/s/1" to device and (if successful) updates attribute 'switch'.
		Command: off():	Sends "/s/0" to device and (if successful) updates attribute 'switch'.
		Attribute:	switch:	Current value of the switch state (on or off).
	Capability:	Actuator.  Used for rule machine (and others) to access switch commands.
	Capability: Refresh
		Command: refresh().  Forces reading of switchBox state data.
----- Preferences
	device_IP:  Used in manual installation of driver only.
	relay_Number:  Manual installation.  The switchBoxD relay number for this device (0 or 1).
	refreshInterval:  frequency to refresh the attribute 'contact'.  Sets to a default
					  of 1 minute.
	fastPoll: provides a fast polling interval (seconds).  Note that this uses Hub
			  resources and can impact a Hub's perceived performance if near limits.
	nameSync: Coordinates the naming between the device and the Hubitat interface.
			  'Hubitat Label Master' - changes name in bleBox device
			  'bleBox device name master' - changes Hubitat label to name in bleBox device.
*/
//	end-of-file