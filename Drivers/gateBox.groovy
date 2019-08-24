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
8.14.19	Various edits.
08.15.19	1.1.01. Modified implementaton based on design notes.
*/
//	===== Definitions, Installation and Updates =====
def driverVer() { return "1.1.01" }
metadata {
	definition (name: "bleBox gateBox",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/bleBox-Integrations/master/0%20-%20Hubitat/Drivers/gateBox.groovy"
			   ) {
		capability "Garage Door Control"
		capability "Momentary"
		capability "Refresh"
	}
	preferences {
		if (!getDataValue("applicationVersion")) {
			input ("device_IP", "text", title: "Device IP (Current = ${getDataValue("deviceIP")})")
		}
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
	updateDataValue("driverVersion", driverVer())

	logInfo("Debug logging is: ${debug}.")
	logInfo("Description text logging is ${descriptionText}.")
	logInfo("Refresh interval set for every ${refreshInterval} minute(s).")
	refresh()
}


//	===== Commands and Parse Returns =====
def open() {
	logDebug("open: ${device.currentValue("door")}")
	if (device.currentValue("door") == "open") {
		logInfo("Door is already open")
	}
	state.lastCommand = "open"
	sendGetCmd("/s/p", "commandParse")
}
def close() {
	logDebug("open: ${device.currentValue("door")}")
	if (device.currentValue("door") == "closed") {
		logInfo("Door is already closed")
	}
	state.lastCommand = "close"
	sendGetCmd("/s/p", "commandParse")
}
def push() {
	logDebug("secondary")
	sendGetCmd("/s/s", "commandParse")
}
def refresh() {
	logDebug("refresh")
	sendGetCmd("/api/gate/state", "commandParse")
}
def commandParse(response) {
	if(response.status != 200 || response.body == null) {
		logWarn("parseInput: Command generated an error return: ${response.status} / ${response.body}")
		return
	}
	def jsonSlurper = new groovy.json.JsonSlurper()
	def cmdResponse = jsonSlurper.parseText(response.body)

	logDebug("refreshParse: response = ${cmdResponse}")
	def position = cmdResponse.currentPos
	def desiredPos = cmdResponse.desiredPos
	def doorState
	if (position == 100) { doorState = "open" }
	else if (position == 0) { doorState = "closed" }
	else if (state.lastCommand == "open") { doorState = "opening" }
	else if (state.lastCommand == "close") { doorState = "closing" }
	else { doorState = "unknown" }
	sendEvent(name: "door", value: doorState)
	log.info "${device.label} refreshResponse: door = ${doorState}"
}


//	===== Name Sync Capability =====
def syncName() {
	logDebug("syncName. Synchronizing device name and label with master = ${nameSync}")
	if (nameSync == "hub") {
		sendPostCmd("/api/device/set",
					"""{"device":{"deviceName":"${device.label}"}}""",
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
	logInfo("Setting device label to that of the bleBox device.")
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
----- Device Installation Assumptions
	The device is assumed to have a two limit switches installed to detect open and closed
	separately.  If not, the device does not know the open/closed state for commands.
----- Capability and Commands
	Capability: Grarge Door Control.  Control and status of the primary door functions.
		Command:  open.  Opens a closed door.  A press while in-motion is based on how the
						 the actual gate/door hardware works.
		Command:  closed.  Closes an opened door.  Sends message if pressed when door is closed.
		Attribute: door. unknown, open, closing, closed, opening
	Capability: Momentary.  Used to implement the Secondary Control
		Command: push.
	Capability: Refresh
		Command: refresh().  Forces reading of doorBox state data.
----- Preferences
	device_IP:  Used in manual installation of driver only.
	doorSwitch: false is default.  If set to true, door will poll every 10 seconds if
				the detected state is open.  Additionally, if the door switch is not
				installed, the refresh interval will be set to every 30 minutes to
				check the device health (since it is no longer reporting anything of
				value to the interface).  Additionally, fast polling will not work.
	refreshInterval:  frequency to refresh the attribute 'contact'.  Sets to a default
					  of 1 minute.
	nameSync: Coordinates the naming between the device and the Hubitat interface.
			  'Hubitat Label Master' - changes name in bleBox device
			  'bleBox device name master' - changes Hubitat label to name in bleBox device.
*/
//	end-of-file