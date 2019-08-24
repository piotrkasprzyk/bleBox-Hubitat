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
08.15.19	1.1.01. Modified implementaton based on design notes.
*/
//	===== Definitions, Installation and Updates =====
def driverVer() { return "1.1.01" }
metadata {
	definition (name: "bleBox doorBox",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/bleBox-Hubitat/master/Drivers/doorBox.groovy"
			   ) {
		capability "Momentary"
		capability "Contact Sensor"
		capability "Refresh"
	}
	preferences {
		if (!getDataValue("applicationVersion")) {
			input ("device_IP", "text", title: "Device IP (Current = ${getDataValue("deviceIP")})")
		}
		input ("doorSwitch", "bool", title: "Door Switch Installed", defaultValue: false)
		input ("refreshInterval", "enum", title: "Device Refresh Interval (minutes)", 
			   options: ["1", "5", "15", "30"], defaultValue: "1")
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
		if (!device_IP) {
			logWarn("updated:  deviceIP  is not set.")
			return
		}
		updateDataValue("deviceIP", device_IP)
		logInfo("Device IP set to ${getDataValue("deviceIP")}")
	}

	if (doorSwitch == true) {
		switch(refreshInterval) {
			case "1" : runEvery1Minute(refresh); break
			case "5" : runEvery5Minutes(refresh); break
			case "15" : runEvery15Minutes(refresh); break
			default: runEvery30Minutes(refresh)
		}
		logInfo("Refresh interval set for every ${refreshInterval} minute(s).")
		if (!fastPoll || fastPoll == "No") { state.pollInterval = "0" }
		else { state.pollInterval = fastPoll }
		logInfo("Fast Polling set to ${state.pollInterval}")
	} else {
		sendEvent(name: "contact", value: "unknown")
		runEvery30Minutes(refresh)
		logInfo("Door Switch not installed.  Fast Polling disabled and refreshInterval set to 30 minutes.")
	}
	
	if (nameSync == "device" || nameSync == "hub") { syncName() }
	logInfo("Name Synchronization set to ${nameSync}")
	
	if (!fastPoll || fastPoll =="No") { state.pollInterval = "0" }
	else { state.pollInterval = fastPoll }
	
	updateDataValue("driverVersion", driverVer())
	logInfo("Debug logging is: ${debug}.")
	logInfo("Description text logging is ${descriptionText}.")
	refresh()
}


//	===== Commands and Parse Returns =====
def push() {
	logDebug("unlock")
	sendGetCmd("/s/p", "commandParse")
	runIn(5, refresh)
}
def refresh() {
	logDebug("refresh")
	sendGetCmd("/api/gate/state", "commandParse")
}
def quickPoll() {
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
	def contact = "closed"
	if (cmdResponse.currentPos != 0) { contact = "open" }
	sendEvent(name: "contact", value: contact)
	if (state.pollInterval != "0") {
		runIn(state.pollInterval.toInteger(), quickPoll)
	} else if (contact == "open" && doorSwitch == true) {
		runIn(10, refresh)
	}
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
	device.setLabel(cmdResponse.deviceName)
	logInfo("Hubit name for device changed to ${cmdResponse.deviceName}.")
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
	The device is assumed to have a contact (reed switch, etc) installed to detect the
	door position. If not, 
			PREFERENCE 'Door Switch Installed' MUST BE SET TO FALSE!
----- Capability and Commands
	Capability: Momemtary
		Command:  push().  Activates the door unlock pulse for device-defined interval
		Attribute:  none.  doorBox automatically stops pulse.
	Capability: Contact Sensor
		Command: none
		Attribute:  contact (open/closed).  Based on device data.
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
	fastPoll: provides a fast polling interval (seconds).  Note that this uses Hub
			  resources and can impact a Hub's perceived performance if near limits.
	nameSync: Coordinates the naming between the device and the Hubitat interface.
			  'Hubitat Label Master' - changes name in bleBox device
			  'bleBox device name master' - changes Hubitat label to name in bleBox device.
----- Operational Concept in code
	a.	On 'push', the doorBox is sent a command to start the pulse.
	b.	A refresh is sent 5 seconds later to capture the open state of the door.
	c.	A refresh is sent every 5 seconds thereafter until the door is listed as closed.
	d.	This will occur ANY  time the attribute 'contact' is determined to be 'open'.
	d.	Once 'closed', refresh returns to the preferenced refreshInterval.
----- Dashboard recommendation
	Momentary:	control command push
	Contact Sensor:  attribute with colors for safe (closed) and unsafe (open position)
*/
//	end-of-file