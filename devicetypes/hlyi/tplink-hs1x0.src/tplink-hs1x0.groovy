metadata {
	definition (name: "tplink_hs1x0", namespace: "hlyi", author: "H Yi") {
		capability "Switch"
		capability "Refresh"
		capability "Polling"
	}

	simulator {
		status "on": "on/off: 1"
		status "off" : "on/off: 0"

		reply "zcl on-off on" : "on/off: 1"
		reply "zcl on-off off" : "on/off :0"
	}

	tiles {
		standardTile("switch", "device.switch", width: 2, height: 2, canChangeIcon: true) {
			state "off", label: 'Off', action: "switch.on",
				icon: "st.switches.switch.off", backgroundColor: "#ffffff"
			state "on", label: 'On', action: "switch.off",
				icon: "st.switches.switch.on", backgroundColor: "#79b821"
		}

		standardTile("refresh", "capability.refresh", width: 1, height: 1, decoration: "flat") {
			state ("default", label:"Refresh", action:"refresh.refresh", icon:"st.secondary.refresh")
		}

		main("switch")
		details(["switch"])
	}

	command "on"
	command "off"
}

preferences
{
	input("outletIP", "text", title: "Outlet IP", required: true, displayDuringSetup: true)
	input("bridgeIP", "text", title: "Bridge IP", required: true, displayDuringSetup: true)
	input("bridgePort", "text", title: "Bridge Port", required: true, displayDuringSetup: true)
}

// parse events into attributes
def parse(String description)
{
	def msg = parseLanMessage(description)
	log.debug "parsing ${msg}"
}

def refresh()
{
	sendCommand("status")
}

def on()
{
//	log.debug("Tplink HS1x0 'on'")
	sendCommand("on")
	sendEvent(name: "switch", value: "on", isStateChange: true)
}

def off()
{
//	log.debug("Tplink HS1x0 'off'")
	sendCommand("off")
	sendEvent(name: "switch", value: "off", isStateChange: true)
}

def hubActionCallback(response)
{
//	log.debug("Tplink HS1x0 'hubActionCallback': '${device.deviceNetworkId}'")
	//log.debug(response)
	
	def status = response?.headers["x-srtb-status"] ?: ""

//	log.debug("Tplink HS1x0 switch status: '${status}'")
	if (status != "Ok") {
		log.debug("Reponse error: " + status)
		return
	}
	def retstr = decryptstr(response?.body)
	if ( retstr == '' ) return
//	log.debug("Decode Str: " + retstr)
	def jsp = new groovy.json.JsonSlurper().parseText(retstr)
	def state = jsp.system?.get_sysinfo?.relay_state
	if ( state == 1 || state == 0 ){
		status = "on"
		if ( state == 0 ) status = "off"
		sendEvent(name: "switch", value: status, isStateChange: true)
//		log.debug("Send event " + status)
	}
}

def poll()
{
	sendCommand("status")
}

def decryptstr(string)
{
//	log.debug("Decoding: " + string)
	def bytes = string.decodeBase64()
//	log.debug("Size of return data: " + bytes.size())
	def size = (bytes[0]<<24) + (bytes[1]<<16) + (bytes[2]<<8) + bytes[3] + 4
	if ( size != bytes.size() ){
		log.debug("Error: unexpected size, expected " + size + ", got " + bytes.size())
		return ''
	}
	def outstr = new byte[size-4]
	def byte key = 171
	def byte tmp
	for ( def i = 4; i < size ; i++){
		tmp = bytes[i]
		outstr[i-4] = bytes[i] ^ key
		key = tmp
	}
//	log.debug ("ECODE: " + outstr.collect{ String.format('%02x', it )}.join() )
	return new String(outstr)
//	log.debug("Decoded: " + str)
//	return str
}

def encryptstr(string)
{
//	log.debug ("CMDSTR " + string)
	def byte key = 171
	def strbytes = string.getBytes()
	def size = strbytes.size()
	def outstr = new byte[4+size]
	outstr[0] = (size>>24) 
	outstr[1] = (size>>16)&0xff
	outstr[2] = (size>>8)&0xff
	outstr[3] = size&0xff
	for (def i = 0 ; i < size; i++) {
		key = strbytes[i] ^key
		outstr[i+4] = key
	}
//	log.debug ("ECODE: " + outstr.collect{ String.format('%02x', it )}.join() )
	return outstr.encodeBase64()
}

private sendCommand(command)
{
	def cmdstr;
	if ( command == 'on' || command == 'off' ) {
		cmdstr = '{"system":{"set_relay_state":{"state":' + (command == 'on' ? 1 : 0 ) + '}}}'
	}else if ( command == 'status' ) {
		cmdstr = '{"system":{"get_sysinfo":{}}}'
	}else {
		return ''
	}
	def data = encryptstr(cmdstr)

//	log.debug ( "DATA : " + data )
	def bridgeIPHex = convertIPtoHex(bridgeIP)
	def bridgePortHex = convertPortToHex(bridgePort)
	def deviceNetworkId = "$bridgeIPHex:$bridgePortHex"
//	log.debug ("Tplink HS1x0 networkid ${device.deviceNetworkId}")
//	log.debug ("Tplink HS1x0 networkid ${deviceNetworkId}")
//	log.debug ("Tplink HS1x0 bridge port: $bridgeIP:$bridgePort")
	
	def headers = [:] 
	headers.put("HOST", "$bridgeIP:$bridgePort")	
	headers.put("x-srtb-ip", outletIP)
	headers.put("x-srtb-port", '9999')
	headers.put("x-srtb-data", data)
	//log.debug("x-hs1x0-ip: '$switchIP'")	
	//log.debug("sendCommand: '${command}'")
	try {
		sendHubCommand(new physicalgraph.device.HubAction([
			method: "GET",
			path: "/",
			headers: headers], 
			deviceNetworkId,
			[callback: "hubActionCallback"]
		)) 
	} catch (e) {
		log.debug("Http Error: " + e.message)
	}
}

private String convertIPtoHex(ipAddress) {
	String hex = ipAddress.tokenize('.').collect{ String.format('%02x', it.toInteger() )}.join()
	return hex
}

private String convertPortToHex(port) {
	String hexport = port.toString().format('%04x', port.toInteger() )
	return hexport
}
