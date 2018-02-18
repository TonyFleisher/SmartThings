/**
 * August Pro 3rd Gen Smart Lock
 *
 * v0.01 TonyFleisher
 *  - Initial release
 *  - Known issues: Lock/Unlock with Keypad has not been tested (I don't have a keypad).
 *
 * If you find any problems or bugs, please open an issue in github and include debug logs in the report.
 *
 * Notes/Quirks:
 *  1. After removing battery, DoorLockOperationReport always reports door as open when it comes back online
 *  2. Lock ignores doorLockConfigurationSet (you cannot set or change autolock settings via zwave)
 *  3. Door will not respond to unlock (doorLockOperationSet) when lockMode is DOOR_LOCK_MODE_DOOR_UNSECURED_WITH_TIMEOUT
 *  4. When door is unlocked with DOOR_LOCK_MODE_DOOR_UNSECURED, Lock will be unlocked with timeout if auto-lock is enabled in the lock settings (via August App)
 *  5. DoorLockOperationReport reports configured auto-lock in lockTimeoutMinutes/lockTimeoutSeconds (not the REMAINING value)
 *  6. On any change in lock state (i.e. locked to unlocked) lock sends Notification - AccessControl (AlarmReport with zwaveAlarmType: 6)
 *  7. On any DoorLockOperationSet, lock sends DoorLockOperationReport before executing and another DoorLockOperationReport if state changes.
 *  8. On any change in door open/closed state, lock sends DoorLockOperationReport
 *  9. Although device supports Manufacturer Specific V2, serial number is not available from Device Specific Get.
 * 10. BatteryReport seems to always report 100%, regardless of battery state
 * 11. DoorLockConfigurationReport always reports operationType: 1, even when autolock is enabled
 * 12. No event is sent when a wrong code is entered on keypad.
 * 13. No event is sent when the keypad "august" button is pressed and the lock is already locked.
 * 14. When the lock is jammed, DoorLockOperationReport will report it as unlocked (there is no unknown state available).
 * 15. If the lock is jammed and auto-lock is enabled, the auto-lock will continue to try to secure the lock.
 *
 *  Adapted by tfleisher from "Z-Wave Lock"
 *  Copyright 2018 TonyFleisher
 *  Copyright 2015 SmartThings
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *
 *
 */
metadata {
	definition (name: "August Smart Lock Pro", namespace: "tfleisher", author: "TonyFleisher") {
		capability "Actuator"
		capability "Lock"
		capability "Polling"
		capability "Refresh"
		capability "Sensor"
		capability "Battery"
		capability "Health Check"
		capability "Configuration"
		attribute "lock", "enum", ["locked","unlocked", "unlocked with timeout","unknown","jammed"]
		attribute "lastLockStatus", "enum", ["jammed","success","unknown"]
		attribute "serialNumber", "string"

		fingerprint mfr:"033F", prod:"0001", model:"0001", deviceJoinName: "August Smart Lock Pro"
		command "testCmd"
	}

	simulator {

		status "locked": "command: 9881, payload: 00 62 03 FF 00 00 FE FE"
		status "unlocked": "command: 9881, payload: 00 62 03 00 00 00 FE FE"

		reply "9881006201FF,delay 4200,9881006202": "command: 9881, payload: 00 62 03 FF 00 00 FE FE"
		reply "988100620100,delay 4200,9881006202": "command: 9881, payload: 00 62 03 00 00 00 FE FE"
	}

	tiles(scale: 2) {
		multiAttributeTile(name:"toggle", type: "generic", width: 6, height: 4){
			tileAttribute ("device.lock", key: "PRIMARY_CONTROL") {
				attributeState "locked", label:'locked', action:"lock.unlock", icon:"st.locks.lock.locked", backgroundColor:"#00A0DC", nextState:"unlocking"
				attributeState "unlocked", label:'unlocked', action:"lock.lock", icon:"st.locks.lock.unlocked", backgroundColor:"#e86d13", nextState:"locking"
				attributeState "unlocked with timeout", label:'unlocked', action:"lock.lock", icon:"st.locks.lock.unlocked", backgroundColor:"#ffffff", nextState:"locking"
				attributeState "locking", label:'locking', icon:"st.locks.lock.locked", backgroundColor:"#00A0DC"
				attributeState "unlocking", label:'unlocking', icon:"st.locks.lock.unlocked", backgroundColor:"#ffffff"
                attributeState "unknown", label:'jammed', action:"lock.lock", icon:"st.secondary.activity", backgroundColor:"#E86D13"
			}
		}
		standardTile("lock", "device.lock", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "default", label:'lock', action:"lock.lock", icon:"st.locks.lock.locked", nextState:"locking"
		}
		standardTile("unlock", "device.lock", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "default", label:'unlock', action:"lock.unlock", icon:"st.locks.lock.unlocked", nextState:"unlocking"
		}

		valueTile("battery", "device.battery", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "battery", label:'${currentValue}% battery', unit:""
		}
		standardTile("refresh", "device.lock", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "default", label:'', action:"refresh.refresh", icon:"st.secondary.refresh"
		}
		standardTile("test", "device.test", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "default", label:'test2', action:"testCmd"
		}
//		valueTile("serialNumber", "device.serialNumber", inactiveLabel: false, decoration: "flat", width: 4, height: 2) {
//			state "serialNumber", label:'S/N: ${currentValue}', unit:""
//		}

		main "toggle"
		details(["toggle", "lock", "unlock", "battery", "refresh"])
//		details(["toggle", "lock", "unlock", "battery", "refresh","test"])
	}
	
	preferences {
		input name: "isDoorSenseEnabled", type: "bool", title: "Enable DoorSense?",
			required: true, displayDuringSetup: true, defaultValue: true
		input name: "isReverseDoor", type: "bool", title: "Reverse open/close status?",
			required: true, displayDuringSetup: true, defaultValue: false
	}
}

import physicalgraph.zwave.commands.doorlockv1.*
import physicalgraph.zwave.commands.notificationv3.*

def testCmd() {
	log.trace "TestCmd"
	log.debug "State: ${state}"
	def cmds = []
	//cmds << zwave.associationV2.associationGet(groupingIdentifier:1)
	//cmds << zwave.powerlevelV1.powerlevelGet()
	cmds << zwave.doorLockV1.doorLockConfigurationSet(lockTimeoutMinutes: 1, lockTimeoutSeconds: 10, operationType: DoorLockConfigurationSet.OPERATION_TYPE_TIMED_OPERATION)
	cmds << zwave.doorLockV1.doorLockConfigurationGet()
	cmds << zwave.doorLockV1.doorLockOperationSet(doorLockMode: DoorLockOperationSet.DOOR_LOCK_MODE_DOOR_UNSECURED_WITH_TIMEOUT)
	def hubAction = response(secureSequence(cmds))

	return hubAction
}
/**
 * Called on app installed
 */
def installed() {
	// Device-Watch pings if no device events received for 1 hour (checkInterval)
	sendEvent(name: "checkInterval", value: 1 * 60 * 60, displayed: false, data: [protocol: "zwave", hubHardwareId: device.hub.hardwareID])
}

/**
 * Executed when the user taps on the 'Done' button on the device settings screen. Sends the values to lock.
 *
 * @return hubAction: The commands to be executed
 */
def updated() {

	log.trace "updated()"
	if (state.updatedLastRanAt && now() <= state.updatedLastRanAt + 5000) {
		log.debug "skipping updating"
		return null
	}
	state.updatedLastRanAt = now()
	log.debug "PREFERENCES:"
	log.debug "Door sense enabled?: ${ isDoorSenseEnabled}"
	log.debug "Reverse door?: ${ isReverseDoor}"
	// Device-Watch pings if no device events received for 1 hour (checkInterval)
	sendEvent(name: "checkInterval", value: 1 * 60 * 60, displayed: false, data: [protocol: "zwave", hubHardwareId: device.hub.hardwareID])
	
	def childDevices = getChildDevices()
	log.debug "Child device: ${childDevices}"
	if (isDoorSenseEnabled && (childDevices == null || childDevices.size == 0)) {
		log.debug "Adding Child"
		def newDevicelabel = "${device.displayName} DoorSense"
		addChildDevice("August Smart Lock Pro DoorSense", "${device.deviceNetworkId}-door", null,
			[ isComponent: false, completedSetup: true, componentName:"doorSense", componentLabel: "DoorSense", label: newDevicelabel])
	}
	
	if (!isDoorSenseEnabled && childDevices && childDevices.size > 0) {
		log.debug "Removing Child"
		deleteChildDevice("${device.deviceNetworkId}-door")
	}
	
	def hubAction = null
	try {
		def cmds = []
		log.trace "init: ${ state.init}"
		log.trace "configured: ${ state.configured}"
		log.trace "MSR: ${ state.MSR}"
		log.trace "fw: ${ state.fw}"
		log.trace "SN: ${ device.currentValue('serialNumber')}"
		if (!state.init || !state.configured) {
			state.init = true
			log.debug "Returning commands for lock operation get and battery get"
			if (!state.configured) {
				cmds << doConfigure()
			}

		}
		// Refresh important status
		cmds += getRefreshCmds()
		if (!state.MSR) {
			cmds << zwave.manufacturerSpecificV2.manufacturerSpecificGet()
		}
		if (!device.currentValue('serialNumber')) {
			cmds << zwave.manufacturerSpecificV2.deviceSpecificGet()
		}
		if (!state.fw) {
			cmds << zwave.versionV1.versionGet()
		}
		log.trace "updated() returning cmds:${ cmds}"
		hubAction = response(secureSequence(cmds, 4200))
	} catch (e) {
		log.warn "updated() threw $e"
	}
	hubAction
}

/**
 * Configures the device to settings needed by SmarthThings at device discovery time
 *
 */
def configure() {
	log.trace "[DTH] Executing 'configure()' for device ${device.displayName}"
	def cmds = doConfigure()
	log.debug "Configure returning with commands := $cmds"
	secureSequence(cmds)
}

/**
 * Returns the list of commands to be executed when the device is being configured/paired
 *
 */
def doConfigure() {
	log.trace "[DTH] Executing 'doConfigure()' for device ${device.displayName}"
	state.configured = true

	def cmds = []
	cmds << zwave.doorLockV1.doorLockOperationGet()
	cmds << zwave.batteryV1.batteryGet()

	log.debug "Do configure returning with commands := $cmds"
	cmds
}

/**
 * Responsible for parsing incoming device messages to generate events
 *
 * @param description: The incoming description from the device
 *
 * @return result: The list of events to be sent out
 *
 */
def parse(String description) {//
	log.trace "[DTH] Executing 'parse(String description)' for device ${device.displayName} with description = $description"

	def result = null
	if (description.startsWith("Err")) {
		if (state.sec) {
			result = createEvent(descriptionText:description, isStateChange:true, displayed:false)
		} else {
			result = createEvent(
					descriptionText: "This lock failed to complete the network security key exchange. If you are unable to control it via SmartThings, you must remove it from your network and add it again.",
					eventType: "ALERT",
					name: "secureInclusion",
					value: "failed",
					displayed: true,
					)
		}
	} else {
		def cmd = zwave.parse(description, [ 0x98: 1, 0x72: 2, 0x85: 2, 0x86: 1 ])
		if (cmd) {
			result = zwaveEvent(cmd)
		}
	}
	log.info "[DTH] parse() - returning result=$result"
	result
}

def zwaveEvent(physicalgraph.zwave.commands.notificationv3.NotificationReport cmd) {
	def type = cmd.notificationType
	log.trace "[DTH] Executing 'zwaveEvent(physicalgraph.zwave.commands.notificationv3.NotificationReport cmd)' with cmd = $cmd"
	return null
}

def zwaveEvent(physicalgraph.zwave.commands.notificationv3.NotificationSupportedReport cmd) {
	def type = cmd.notificationType
	log.trace "[DTH] Executing 'zwaveEvent(physicalgraph.zwave.commands.notificationv3.NotificationSupportedReport cmd)' with cmd = $cmd"
	return null
}

def zwaveEvent(physicalgraph.zwave.commands.notificationv3.EventSupportedReport cmd) {
	def type = cmd.notificationType
	log.trace "[DTH] Executing 'zwaveEvent(physicalgraph.zwave.commands.notificationv3.EventSupportedReport cmd)' with cmd = $cmd"
	return null
}

/**
 * Responsible for parsing ConfigurationReport command
 *
 * @param cmd: The ConfigurationReport command to be parsed
 *
 * @return The event(s) to be sent out
 *
 */
def zwaveEvent(physicalgraph.zwave.commands.configurationv2.ConfigurationReport cmd) {
	log.trace "[DTH] Executing 'zwaveEvent(physicalgraph.zwave.commands.configurationv2.ConfigurationReport cmd)' with cmd = $cmd"

	return null
}

/**
 * Responsible for parsing SecurityMessageEncapsulation command
 *
 * @param cmd: The SecurityMessageEncapsulation command to be parsed
 *
 * @return The event(s) to be sent out
 *
 */
def zwaveEvent(physicalgraph.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
	log.trace "[DTH] Executing 'zwaveEvent(physicalgraph.zwave.commands.securityv1.SecurityMessageEncapsulation)' with cmd = $cmd"
	def encapsulatedCommand = cmd.encapsulatedCommand([0x62: 1, 0x71: 2, 0x80: 1, 0x85: 2, 0x63: 1, 0x98: 1, 0x86: 1])
	if (encapsulatedCommand) {
		zwaveEvent(encapsulatedCommand)
	}
}

/**
 * Responsible for parsing NetworkKeyVerify command
 *
 * @param cmd: The NetworkKeyVerify command to be parsed
 *
 * @return The event(s) to be sent out
 *
 */
def zwaveEvent(physicalgraph.zwave.commands.securityv1.NetworkKeyVerify cmd) {
	log.trace "[DTH] Executing 'zwaveEvent(physicalgraph.zwave.commands.securityv1.NetworkKeyVerify)' with cmd = $cmd"
	createEvent(name:"secureInclusion", value:"success", descriptionText:"Secure inclusion was successful", isStateChange: true)
}

/**
 * Responsible for parsing SecurityCommandsSupportedReport command
 *
 * @param cmd: The SecurityCommandsSupportedReport command to be parsed
 *
 * @return The event(s) to be sent out
 *
 */
def zwaveEvent(physicalgraph.zwave.commands.securityv1.SecurityCommandsSupportedReport cmd) {
	log.trace "[DTH] Executing 'zwaveEvent(physicalgraph.zwave.commands.securityv1.SecurityCommandsSupportedReport)' with cmd = $cmd"
	state.sec = cmd.commandClassSupport.collect { String.format("%02X ", it) }.join()
	if (cmd.commandClassControl) {
		state.secCon = cmd.commandClassControl.collect { String.format("%02X ", it) }.join()
	}
	createEvent(name:"secureInclusion", value:"success", descriptionText:"Lock is securely included", isStateChange: true)
}

def zwaveEvent(physicalgraph.zwave.commands.doorlockv1.DoorLockConfigurationReport cmd) {
	log.trace "[DTH] Executing 'zwaveEvent(DoorLockConfigurationReport)' with cmd = $cmd"
//cmd = DoorLockConfigurationReport(insideDoorHandlesState: 1, lockTimeoutMinutes: 254, lockTimeoutSeconds: 254, operationType: 1, outsideDoorHandlesState: 0)

}

/**
 * Responsible for parsing DoorLockOperationReport command
 *
 * @param cmd: The DoorLockOperationReport command to be parsed
 *
 * @return The event(s) to be sent out
 *
 */
def zwaveEvent(DoorLockOperationReport cmd) {
//doorCondition: 3 doorLockMode: 1, insideDoorHandlesMode:1, lockTimeoutMinutes: 2, lockTimeoutSeconds: 0, outsideDoorHandlesMode: 0
//doorCondition: 2 doorLockMode: 1, insideDoorHandlesMode:1, lockTimeoutMinutes: 2, lockTimeoutSeconds: 0, outsideDoorHandlesMode: 0
//doorCondition: 3 doorLockMode: 1, insideDoorHandlesMode:1, lockTimeoutMinutes: 2, lockTimeoutSeconds: 0, outsideDoorHandlesMode: 0
//doorCondition: 3 doorLockMode: 255, insideDoorHandlesMode:1, lockTimeoutMinutes: 2, lockTimeoutSeconds: 0, outsideDoorHandlesMode: 0

	log.trace "[DTH] Executing 'zwaveEvent(DoorLockOperationReport)' with cmd = $cmd"
	def result = []
	def associationSet = []
	
	unschedule("followupStateCheck")
	unschedule("stateCheck")
	
	def lockedStatusMap = [ name: "lock" ]
	lockedStatusMap.data = [ lockName: device.displayName ]
	if (cmd.doorLockMode == 0xFF) {
		lockedStatusMap.value = "locked"
		lockedStatusMap.descriptionText = "Locked"
	} else if (cmd.doorLockMode == 0x00) {
		lockedStatusMap.value = "unlocked"
		lockedStatusMap.descriptionText = "Unlocked"
		state.lockTimeoutMinutes = cmd.lockTimeoutMinutes
		state.lockTimeoutSeconds = cmd.lockTimeoutSeconds
	} else if (cmd.doorLockMode == 0x01) {
		lockedStatusMap.value = "unlocked with timeout"
		def lockTimeout = "${ String.format('%02d',cmd.lockTimeoutMinutes)}:${ String.format('%02d',cmd.lockTimeoutSeconds)}"
		lockedStatusMap.descriptionText = "Unlocked with timeout (timeout: ${lockTimeout})"
		state.lockTimeoutMinutes = cmd.lockTimeoutMinutes
		state.lockTimeoutSeconds = cmd.lockTimeoutSeconds
	} else {
		log.trace "DoorLockOperationReport: Unexpected mode: ${cmd.doorLockMode}"
		lockedStatusMap.value = "unknown"
		lockedStatusMap.descriptionText = "DLO Unknown state: ${cmd.doorLockMode}"
	}
	
	if (lockedStatusMap.value == "unlocked" && state.assoc != zwaveHubNodeId) {
		log.trace "DoorLockOperationReport: Sending associationSet"
		associationSet << response(secure(zwave.associationV1.associationSet(groupingIdentifier:1, nodeId:zwaveHubNodeId)))
		associationSet << response(secure(zwave.associationV1.associationGet(groupingIdentifier:1)))
	}
	
	if (cmd.doorCondition & 0x2) {
		log.debug "DoorLockOperationReport: Door condition: unlocked"
	} else {
		log.debug "DoorLockOperationReport: Door condition: locked"
	}
	result << createEvent(lockedStatusMap)
	if (isDoorSenseEnabled) {
		def contactStatusMap = [ name: "contact"]
		if (cmd.doorCondition & 0x1) {
			contactStatusMap.value = "closed"
			contactStatusMap.descriptionText = "Door closed"
			log.debug "DoorLockOperationReport: Door condition: closed"
		} else {
			contactStatusMap.value = "open"
			contactStatusMap.descriptionText = "Door open"
			log.debug "DoorLockOperationReport: Door condition: open"
		}
		result << createDoorSenseEvent(contactStatusMap)
	}
	if (associationSet)
		result << associationSet
	log.trace "DoorLockOperationReport result: ${ result.inspect()}"
	return result
}

private createDoorSenseEvent(contactStatusMap) {
	log.trace "createDoorSenseEvent entry"
	if (!isDoorSenseEnabled) {
		log.debug "Door sense not enabled"
		return null
	}
	
	if (isReverseDoor) {
		log.debug "Reversing contact value"
		if (contactStatusMap.value == "open")
			contactStatusMap.value = "closed"
		if (contactStatusMap.value == "closed")
			contactStatusMap.value = "open"
	}
	
	log.debug "Sending door data to child"
	def childDevices = getChildDevices()
	if (childDevices && childDevices.size > 0) {
		def child = childDevices[0]
		child.sendEvent(contactStatusMap)
	}
	log.trace "createDoorSenseEvent normal exit"
}

/**
 * Responsible for parsing AlarmReport command
 *
 * @param cmd: The AlarmReport command to be parsed
 *
 * @return The event(s) to be sent out
 *
 */
def zwaveEvent(physicalgraph.zwave.commands.alarmv2.AlarmReport cmd) {
	log.trace "[DTH] Executing 'zwaveEvent(physicalgraph.zwave.commands.alarmv2.AlarmReport)' with cmd = $cmd"
	def result = []
	
	if (cmd.zwaveAlarmType == 6) {
		log.trace "[DTH] AlarmReport type 6 (AccessControl)"
		result = handleAccessAlarmReport(cmd)
	} else if (cmd.zwaveAlarmType == 7) {
		log.trace "[DTH] AlarmReport type 7 (HomeSecurity)"
		result = handleBurglarAlarmReport(cmd)
	} else if(cmd.zwaveAlarmType == 8) {
		log.trace "[DTH] AlarmReport type 8 (PowerManagement)"
		result = handleBatteryAlarmReport(cmd)
	} else {
		log.trace "[DTH] AlarmReport type ${cmd.zwaveAlarmType}"
		result = handleAlarmReportUsingAlarmType(cmd)
	}
		
	result = result ?: null
	log.debug "[DTH] zwaveEvent(physicalgraph.zwave.commands.alarmv2.AlarmReport) returning with result = ${ result.inspect()}"
	result
}

def zwaveEvent(physicalgraph.zwave.commands.alarmv2.AlarmTypeSupportedReport cmd) {//
	log.trace "[DTH] Executing 'zwaveEvent(physicalgraph.zwave.commands.alarmv2.AlarmTypeSupportedReport)' with cmd = $cmd"

}

/**
 * Responsible for handling Access AlarmReport command
 *
 * @param cmd: The AlarmReport command to be parsed
 *
 * @return The event(s) to be sent out
 *
 * The August Smart Lock Pro supports the following event types to be logged as part of the Notification Command Class.
 * Manual Lock Operation
 * Manual Unlock Operation
 * RF Lock Operation
 * RF Unlock Operation
 * Keypad Lock Operation
 * Keypad Unlock Operation
 * Auto Lock Locked Operation
 * Lock Jammed
 * 
 * References:
 * https://products.z-wavealliance.org/ProductManual/File?folder=&filename=Manuals/2624/August%20Z-Wave%20Required%20Documentation.pdf
 * http://zwavepublic.com/sites/default/files/SDS12652-13%20-%20Z-Wave%20Command%20Class%20Specification%20N-Z.pdf pp. 75-80
 * http://support.august.com/customer/en/portal/articles/2859771-z-wave-information?b_id=10917&
 */
private def handleAccessAlarmReport(cmd) {//
	log.trace "[DTH] Executing 'handleAccessAlarmReport' with cmd = $cmd"
	def result = []
	def map = null
	def isJammed = false
	def lastLockStatusValue = "unknown"

	def deviceName = device.displayName
	if (1 <= cmd.zwaveAlarmEvent && cmd.zwaveAlarmEvent < 8) {
		map = [ name: "lock", value: (cmd.zwaveAlarmEvent & 1) ? "locked" : "unlocked" ]
	}
	switch(cmd.zwaveAlarmEvent) {
		case 1: // Manually locked
			map.descriptionText = "Locked manually"
			map.data = [ method: (cmd.alarmLevel == 2) ? "keypad" : "manual" ]
			lastLockStatusValue = "success"
			break
		case 2: // Manually unlocked
			map.descriptionText = "Unlocked manually"
			map.data = [ method: "manual" ]
			lastLockStatusValue = "success"
			break
		case 3: // Locked by command
			map.descriptionText = "Locked"
			map.data = [ method: "command" ]
			lastLockStatusValue = "success"
			break
		case 4: // Unlocked by command
			map.descriptionText = "Unlocked"
			map.data = [ method: "command" ]
			lastLockStatusValue = "success"
			break
		case 5: // Locked with keypad
		// TODO: See if we can access details about code used
			map.descriptionText = "Locked with keypad"
			map.data = [method: "keypad"]
			lastLockStatusValue = "success"
			break
		case 6: // Unlocked with keypad
		// TODO: See if we can access details about code used
			map.descriptionText = "Unlocked with keypad"
			map.data = [method: "keypad"]
			lastLockStatusValue = "success"
			break
		case 7: // Not Fully Unlocked (NOT Supported in FW 1.56)
			map = [ name: "lock", value: "unknown", descriptionText: "Manual Not Fully Locked" ]
			map.data = [ method: "manual" ]
			lastLockStatusValue = "fail"
			break
		case 8: // Not Fully Unlocked (NOT Supported in FW 1.56)
			map = [ name: "lock", value: "unknown", descriptionText: "RF Not Fully Locked " ]
			map.data = [ method: "command" ]
			lastLockStatusValue = "fail"
			break
		case 9: // Auto locked
			map = [ name: "lock", value: "locked", data: [ method: "auto" ] ]
			map.descriptionText = "Auto locked"
			lastLockStatusValue = "success"
			break
		case 0xA: // Not Fully Auto locked (NOT Supported in FW 1.56)
			map = [ name: "lock", value: "unknown", descriptionText: "Auto Lock Not Fully" ]
			map.data = [ method: "auto" ]
			lastLockStatusValue = "fail"
			break
		case 0xB: // Jammed
			map = [ name: "lock", value: "unknown", descriptionText: "${device.displayName} Lock Jammed" ]
			lastLockStatusValue = "jammed"
			isJammed = true
			break

		case 0xFE:
			// delegating it to handleAlarmReportUsingAlarmType
			return handleAlarmReportUsingAlarmType(cmd)
		default:
			// delegating it to handleAlarmReportUsingAlarmType
			return handleAlarmReportUsingAlarmType(cmd)
	}
	
	if (map) {
		if (map.data) {
			map.data.lockName = deviceName
		} else {
			map.data = [ lockName: deviceName ]
		}
		result << createEvent(map)
	}
	
	def lastLockStatusMap = [name: 'lastLockStatus', value: lastLockStatusValue]
	result << createEvent(lastLockStatusMap)
	
//	if (isJammed) {
//		// Send status change to jammed
//		def jammedEventMap = [name: "lock", value: "jammed", isStateChange:true, descriptionText: "${device.displayName} Jammed", eventType: "ALERT"]
//		result << createEvent(jammedEventMap)
//	}
	result = result.flatten()
	result
}

/**
 * Responsible for handling Burglar AlarmReport command
 *
 * @param cmd: The AlarmReport command to be parsed
 *
 * @return The event(s) to be sent out
 *
 */
private def handleBurglarAlarmReport(cmd) {
	log.trace "[DTH] Executing 'handleBurglarAlarmReport' with cmd = $cmd"
	def result = []
	def deviceName = device.displayName
	
	def map = [ name: "tamper", value: "detected" ]
	map.data = [ lockName: deviceName ]
	switch (cmd.zwaveAlarmEvent) {
		case 0:
			map.value = "clear"
			map.descriptionText = "Tamper alert cleared"
			break
		case 1:
		case 2:
			map.descriptionText = "Intrusion attempt detected"
			break
		case 3:
			map.descriptionText = "Covering removed"
			break

		default:
			// delegating it to handleAlarmReportUsingAlarmType
			return handleAlarmReportUsingAlarmType(cmd)
	}
	
	result << createEvent(map)
	result
}

/**
 * Responsible for handling Battery AlarmReport command
 *
 * @param cmd: The AlarmReport command to be parsed
 *
 * @return The event(s) to be sent out
 */
private def handleBatteryAlarmReport(cmd) {
	log.trace "[DTH] Executing 'handleBatteryAlarmReport' with cmd = $cmd"
	def result = []
	def deviceName = device.displayName
	def map = null
	switch(cmd.zwaveAlarmEvent) {
		case 0x0A:
			map = [ name: "battery", value: 1, descriptionText: "Battery level critical", displayed: true, data: [ lockName: deviceName ] ]
			break
		case 0x0B:
			map = [ name: "battery", value: 0, descriptionText: "Battery too low to operate lock", isStateChange: true, displayed: true, data: [ lockName: deviceName ] ]
			break
		default:
			// delegating it to handleAlarmReportUsingAlarmType
			return handleAlarmReportUsingAlarmType(cmd)
	}
	result << createEvent(map)
	result
}

/**
 * Responsible for handling AlarmReport commands which are ignored by Access & Burglar handlers
 *
 * @param cmd: The AlarmReport command to be parsed
 *
 * @return The event(s) to be sent out
 *
 */
private def handleAlarmReportUsingAlarmType(cmd) {
	// tf - I don't believe the AugustLock sends anything that would make it to this handler, but leaving it in place just in case.
	log.trace "[DTH] Executing 'handleAlarmReportUsingAlarmType' with cmd = $cmd"
	def result = []
	def map = null
	def deviceName = device.displayName
	switch(cmd.alarmType) {
		case 9:
		case 17:
			map = [ name: "lock", value: "unknown", descriptionText: "Unknown state" ]
			break
		case 16: // Note: for levers this means it's unlocked, for non-motorized deadbolt, it's just unsecured and might not get unlocked
		case 19: // Unlocked with keypad
			map = [ name: "lock", value: "unlocked" ]
			if (cmd.alarmLevel != null) {
				codeID = cmd.alarmLevel
				codeName = "keypad"
				map.descriptionText = "\"$codeName\" unlocked the lock"
				map.data = [ usedCode: codeID, codeName: codeName, method: "keypad" ]
			}
			break
		case 18: // Locked with keypad
			codeID = cmd.alarmLevel
			codeName = "keypad"
			map = [ name: "lock", value: "locked" ]
			map.descriptionText = "\"$codeName\" locked the lock"
			map.data = [ usedCode: codeID, codeName: codeName, method: "keypad" ]
			break
		case 21: // Manually locked
			map = [ name: "lock", value: "locked", data: [ method: (cmd.alarmLevel == 2) ? "keypad" : "manual" ] ]
			map.descriptionText = "Locked manually"
			break
		case 22: // Manually unlocked
			map = [ name: "lock", value: "unlocked", data: [ method: "manual" ] ]
			map.descriptionText = "Unlocked manually"
			break
		case 23:
			map = [ name: "lock", value: "unknown", descriptionText: "Unknown state" ]
			map.data = [ method: "command" ]
			break
		case 24: // Locked by command
			map = [ name: "lock", value: "locked", data: [ method: "command" ] ]
			map.descriptionText = "Locked"
			break
		case 25: // Unlocked by command
			map = [ name: "lock", value: "unlocked", data: [ method: "command" ] ]
			map.descriptionText = "Unlocked"
			break
		case 26:
			map = [ name: "lock", value: "unknown", descriptionText: "Unknown state" ]
			map.data = [ method: "auto" ]
			break
		case 27: // Auto locked
			map = [ name: "lock", value: "locked", data: [ method: "auto" ] ]
			map.descriptionText = "Auto locked"
			break

		case 130:  // Batteries replaced
			map = [ descriptionText: "Batteries replaced", isStateChange: true ]
			break

		case 161: // Tamper Alarm
			if (cmd.alarmLevel == 2) {
				map = [ name: "tamper", value: "detected", descriptionText: "Front escutcheon removed", isStateChange: true ]
			} else {
				map = [ name: "tamper", value: "detected", descriptionText: "Keypad attempts exceed code entry limit", isStateChange: true, displayed: true ]
			}
			break
		case 167: // Low Battery Alarm
			if (!state.lastbatt || now() - state.lastbatt > 12*60*60*1000) {
				map = [ descriptionText: "Battery low", isStateChange: true ]
				result << response(secure(zwave.batteryV1.batteryGet()))
			} else {
				map = [ name: "battery", value: device.currentValue("battery"), descriptionText: "Battery low", isStateChange: true ]
			}
			break
		case 168: // Critical Battery Alarms
			map = [ name: "battery", value: 1, descriptionText: "Battery level critical", displayed: true ]
			break
		case 169: // Battery too low to operate
			map = [ name: "battery", value: 0, descriptionText: "Battery too low to operate lock", isStateChange: true, displayed: true ]
			break
		default:
			map = [ displayed: false, descriptionText: "Alarm event ${cmd.alarmType} level ${cmd.alarmLevel}" ]
			break
	}
	
	if (map) {
		if (map.data) {
			map.data.lockName = deviceName
		} else {
			map.data = [ lockName: deviceName ]
		}
		result << createEvent(map)
	}
	result = result.flatten()
	log.debug "handleAlarmReportUsingAlarmType is returning: ${ result.inspect()}"
	result
}


/**
 * Responsible for parsing AssociationReport command
 *
 * @param cmd: The AssociationReport command to be parsed
 *
 * @return The event(s) to be sent out
 *
 */
def zwaveEvent(physicalgraph.zwave.commands.associationv2.AssociationReport cmd) {//
	log.trace "[DTH] Executing 'zwaveEvent(physicalgraph.zwave.commands.associationv2.AssociationReport)' with cmd = $cmd"
	def result = []
	if (cmd.nodeId.any { it == zwaveHubNodeId }) {
		state.remove("associationQuery")
		state["associationQuery"] = null
		result << createEvent(descriptionText: "Is associated")
		state.assoc = zwaveHubNodeId
	} else if (cmd.groupingIdentifier == 1) {
		log.debug("AssociationReport: Sending associationSet")
		result << response(secure(zwave.associationV2.associationSet(groupingIdentifier:1, nodeId:zwaveHubNodeId)))
	}
	result
}

/**
 * Responsible for parsing TimeGet command
 *
 * @param cmd: The TimeGet command to be parsed
 *
 * @return The event(s) to be sent out
 *
 */
def zwaveEvent(physicalgraph.zwave.commands.timev1.TimeGet cmd) {
	log.trace "[DTH] Executing 'zwaveEvent(physicalgraph.zwave.commands.timev1.TimeGet)' with cmd = $cmd"
	def result = []
	def now = new Date().toCalendar()
	if(location.timeZone) now.timeZone = location.timeZone
	result << createEvent(descriptionText: "Requested time update", displayed: false)
	result << response(secure(zwave.timeV1.timeReport(
		hourLocalTime: now.get(Calendar.HOUR_OF_DAY),
		minuteLocalTime: now.get(Calendar.MINUTE),
		secondLocalTime: now.get(Calendar.SECOND)))
	)
	result
}

/**
 * Responsible for parsing BasicSet command
 *
 * @param cmd: The BasicSet command to be parsed
 *
 * @return The event(s) to be sent out
 *
 */
def zwaveEvent(physicalgraph.zwave.commands.basicv1.BasicSet cmd) {
	log.trace "[DTH] Executing 'zwaveEvent(physicalgraph.zwave.commands.basicv1.BasicSet)' with cmd = $cmd"

}

/**
 * Responsible for parsing BatteryReport command
 *
 * @param cmd: The BatteryReport command to be parsed
 *
 * @return The event(s) to be sent out
 *
 */
def zwaveEvent(physicalgraph.zwave.commands.batteryv1.BatteryReport cmd) {//
	log.trace "[DTH] Executing 'zwaveEvent(physicalgraph.zwave.commands.batteryv1.BatteryReport)' with cmd = $cmd"
//	def map = [ name: "battery", unit: "%", isStateChange: true ]
	def map = [ name: "battery", unit: "%" ]
	if (cmd.batteryLevel == 0xFF) {
		map.value = 1
		map.descriptionText = "Has a low battery"
	} else {
		map.value = cmd.batteryLevel
		map.descriptionText = "Battery is at ${cmd.batteryLevel}%"
	}
	state.lastbatt = now()
	createEvent(map)
}

/**
 * Responsible for parsing ManufacturerSpecificReport command
 *
 * @param cmd: The ManufacturerSpecificReport command to be parsed
 *
 * @return The event(s) to be sent out
 *
 */
def zwaveEvent(physicalgraph.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd) {//
	log.trace "[DTH] Executing 'zwaveEvent(physicalgraph.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport)' with cmd = $cmd"
	def result = []
	def msr = String.format("%04X-%04X-%04X", cmd.manufacturerId, cmd.productTypeId, cmd.productId)
	updateDataValue("MSR", msr)
	result << createEvent(descriptionText: "MSR: $msr", isStateChange: false)
	result
}

/** Parse DeviceSpecificReport **/
def zwaveEvent(physicalgraph.zwave.commands.manufacturerspecificv2.DeviceSpecificReport cmd) {
	log.trace "[DTH] Executing 'zwaveEvent(physicalgraph.zwave.commands.manufacturerspecificv2.DeviceSpecificReport)' with cmd = $cmd"
	log.debug "deviceIdData:                ${cmd.deviceIdData}"
	log.debug "deviceIdDataFormat:          ${cmd.deviceIdDataFormat}"
	log.debug "deviceIdDataLengthIndicator: ${cmd.deviceIdDataLengthIndicator}"
	log.debug "deviceIdType:                ${cmd.deviceIdType}"

	if (cmd.deviceIdType == 1 && cmd.deviceIdDataFormat == 1) {//serial number in binary format
		String serialNumber = "h'"

		cmd.deviceIdData.each{ data ->
			serialNumber += "${String.format("%02X", data)}"
		}

		createEvent(name: "serialNumber", value: serialNumber)
		log.debug "${device.displayName} - serial number: ${serialNumber}"
	} else {
		log.debug "${device.displayName} did not return serial number"
		createEvent(name: "serialNumber", value: "unavailable")
	}
}

/**
 * Responsible for parsing VersionReport command
 *
 * @param cmd: The VersionReport command to be parsed
 *
 * @return The event(s) to be sent out
 *
 */
def zwaveEvent(physicalgraph.zwave.commands.versionv1.VersionReport cmd) { //
//[DTH] Executing 'zwaveEvent(physicalgraph.zwave.commands.versionv1.VersionReport)' with cmd = VersionReport(applicationSubVersion: 56, applicationVersion: 1, zWaveLibraryType: 3, zWaveProtocolSubVersion: 61, zWaveProtocolVersion: 4)
	log.trace "[DTH] Executing 'zwaveEvent(physicalgraph.zwave.commands.versionv1.VersionReport)' with cmd = $cmd"
	def fw = "${cmd.applicationVersion}.${cmd.applicationSubVersion}"
	updateDataValue("fw", fw)

	def text = "${device.displayName}: firmware version: $fw, Z-Wave version: ${cmd.zWaveProtocolVersion}.${cmd.zWaveProtocolSubVersion}"
	createEvent(descriptionText: text, isStateChange: false)
}

/**
*/
def zwaveEvent(physicalgraph.zwave.commands.powerlevelv1.PowerlevelReport cmd) {
	log.trace "[DTH] Executing 'zwaveEvent(physicalgraph.zwave.commands.powerlevelv1.PowerlevelReport)' with cmd = $cmd"

}

/**
 * Responsible for parsing ApplicationBusy command
 *
 * @param cmd: The ApplicationBusy command to be parsed
 *
 * @return The event(s) to be sent out
 *
 */
def zwaveEvent(physicalgraph.zwave.commands.applicationstatusv1.ApplicationBusy cmd) {
	log.trace "[DTH] Executing 'zwaveEvent(physicalgraph.zwave.commands.applicationstatusv1.ApplicationBusy)' with cmd = $cmd"
	def msg = cmd.status == 0 ? "try again later" :
			  cmd.status == 1 ? "try again in ${cmd.waitTime} seconds" :
			  cmd.status == 2 ? "request queued" : "sorry"
	createEvent(displayed: true, descriptionText: "Is busy, $msg")
}

/**
 * Responsible for parsing ApplicationRejectedRequest command
 *
 * @param cmd: The ApplicationRejectedRequest command to be parsed
 *
 * @return The event(s) to be sent out
 *
 */
def zwaveEvent(physicalgraph.zwave.commands.applicationstatusv1.ApplicationRejectedRequest cmd) {
	log.trace "[DTH] Executing 'zwaveEvent(physicalgraph.zwave.commands.applicationstatusv1.ApplicationRejectedRequest)' with cmd = $cmd"
	createEvent(displayed: true, descriptionText: "Rejected the last request")
}

/**
 * Responsible for parsing zwave command
 *
 * @param cmd: The zwave command to be parsed
 *
 * @return The event(s) to be sent out
 *
 * NOTE: This is used if there is no zwaveEvent() method defined to recieve the specific message type
 */
def zwaveEvent(physicalgraph.zwave.Command cmd) { // fallback
	log.trace "[DTH] Executing 'zwaveEvent(physicalgraph.zwave.Command)' with cmd = $cmd"
	//createEvent(displayed: false, descriptionText: "$cmd")
}

/**
 * Executes lock and then check command with a delay on a lock
 */
def lockAndCheck(doorLockMode) {
/* doorLockOperationSet automatically triggers LockOperationReport, so no need to request another one */
	secureSequence([
		zwave.doorLockV1.doorLockOperationSet(doorLockMode: doorLockMode)
//		zwave.doorLockV1.doorLockOperationSet(doorLockMode: doorLockMode),
//		zwave.doorLockV1.doorLockOperationGet()
				], 4200)
	
}

/**
 * Executes lock command on a lock
 */
def lock() {
	log.trace "[DTH] Executing lock() for device ${device.displayName}"
	lockAndCheck(DoorLockOperationSet.DOOR_LOCK_MODE_DOOR_SECURED)
}

/**
 * Executes unlock command on a lock
 */
def unlock() {
	log.trace "[DTH] Executing unlock() for device ${device.displayName}"
	lockAndCheck(DoorLockOperationSet.DOOR_LOCK_MODE_DOOR_UNSECURED)
	//lockAndCheck(DoorLockOperationSet.DOOR_LOCK_MODE_DOOR_UNSECURED_WITH_TIMEOUT)
}

/**
 * Executes unlock with timeout command on a lock
 */
//def unlockWithTimeout() {
//	log.trace "[DTH] Executing unlockWithTimeout() for device ${device.displayName}"
//	lockAndCheck(DoorLockOperationSet.DOOR_LOCK_MODE_DOOR_UNSECURED_WITH_TIMEOUT)
//}

/**
 * PING is used by Device-Watch in attempt to reach the Device
 */
def ping() {
	log.trace "[DTH] Executing ping() for device ${device.displayName}"
	runIn(30, followupStateCheck)
	secure(zwave.doorLockV1.doorLockOperationGet())
}

/**
 * Checks the door lock state. Also, schedules checking of door lock state every one hour.
 */
def followupStateCheck() {
	log.trace "[DTH] followUpStateCheck"
	runEvery1Hour(stateCheck)
	stateCheck()
}

/**
 * Checks the door lock state
 */
def stateCheck() {
	log.trace "stateCheck()"
	sendHubCommand(new physicalgraph.device.HubAction(secure(zwave.doorLockV1.doorLockOperationGet())))
}

/**
 * Called when the user taps on the refresh button
 */
def refresh() {
	log.trace "[DTH] Executing refresh() for device ${device.displayName}"
	log.debug "state: ${ state}"
	def cmds = getRefreshCmds()
	secureSequence(cmds, 4000)
}

private getRefreshCmds() {
	def cmds = []
	cmds << zwave.doorLockV1.doorLockOperationGet()
	cmds << zwave.batteryV1.batteryGet()

	if (!state.associationQuery) {
		log.debug "refresh: getting association"
		cmds << zwave.associationV2.associationGet(groupingIdentifier:1)
		state.associationQuery = now()
	} else if (now() - state.associationQuery.toLong() > 9000) {
		log.debug "refresh: resetting association"
		cmds << zwave.associationV2.associationSet(groupingIdentifier:1, nodeId:zwaveHubNodeId)
		cmds << zwave.associationV2.associationGet(groupingIdentifier:1)
		state.associationQuery = now()
	}
	log.debug "refresh() cmds: ${ cmds}"
	return cmds
}
/**
 * Called by the Smart Things platform in case Polling capability is added to the device type
 */
def poll() {
	log.trace "[DTH] Executing poll() for device ${device.displayName}"
	def cmds = []
	// Only check lock state if it changed recently or we haven't had an update in an hour
	def latest = device.currentState("lock")?.date?.time
	if (!latest || !secondsPast(latest, 6 * 60) || secondsPast(state.lastPoll, 55 * 60)) {
		cmds << zwave.doorLockV1.doorLockOperationGet()
		state.lastPoll = now()
	} else if (!state.lastbatt || now() - state.lastbatt > 53*60*60*1000) {
		cmds << zwave.batteryV1.batteryGet()
		state.lastbatt = now()
	}
	if (state.assoc != zwaveHubNodeId && secondsPast(state.associationQuery, 19 * 60)) {
		cmds << zwave.associationV1.associationSet(groupingIdentifier:1, nodeId:zwaveHubNodeId)
		cmds << zwave.associationV1.associationGet(groupingIdentifier:1)
		state.associationQuery = now()
	} else {
		// Only check lock state once per hour
		if (secondsPast(state.lastPoll, 55 * 60)) {
			cmds << zwave.doorLockV1.doorLockOperationGet()
			state.lastPoll = now()
		} else if (!state.MSR) {
			cmds << zwave.manufacturerSpecificV1.manufacturerSpecificGet().format()
		} else if (!state.fw) {
			cmds << zwave.versionV1.versionGet().format()
		} else if (!state.lastbatt || now() - state.lastbatt > 53*60*60*1000) {
			cmds << zwave.batteryV1.batteryGet()
		}
	}

	if (cmds) {
		log.debug "poll is sending ${cmds.inspect()}"
		secureSequence(cmds)
	} else {
		// workaround to keep polling from stopping due to lack of activity
		sendEvent(descriptionText: "skipping poll", isStateChange: true, displayed: false)
		null
	}
}


/**
 * Encapsulates a command
 *
 * @param cmd: The command to be encapsulated
 *
 * @returns ret: The encapsulated command
 */
private secure(physicalgraph.zwave.Command cmd) {
	zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
}

/**
 * Encapsulates list of command and adds a delay
 *
 * @param commands: The list of command to be encapsulated
 *
 * @param delay: The delay between commands
 *
 * @returns The encapsulated commands
 */
private secureSequence(commands, delay=4200) {
	delayBetween(commands.collect{ secure(it) }, delay)
}

/**
 * Checks if the time elapsed from the provided timestamp is greater than the number of senconds provided
 *
 * @param timestamp: The timestamp
 *
 * @param seconds: The number of seconds
 *
 * @returns true if elapsed time is greater than number of seconds provided, else false
 */
private Boolean secondsPast(timestamp, seconds) {
	if (!(timestamp instanceof Number)) {
		if (timestamp instanceof Date) {
			timestamp = timestamp.time
		} else if ((timestamp instanceof String) && timestamp.isNumber()) {
			timestamp = timestamp.toLong()
		} else {
			return true
		}
	}
	return (now() - timestamp) > (seconds * 1000)
}
