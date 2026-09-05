/**
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
 *    Nexia Thermostat Service Manager
 *
 *    Author: Trent Foley
 *    Date: 2016-01-19
 *
 * **	Modifications **
 *	Date		Who		    Description
 *	2022-09-15	thebearmay	Port to Hubitat
 *	2022-09-16	thebearmay	Fix thermostatOperatingMode
 *  2022-10-04  thebearmay  Add permanent hold and return to schedule
 *  2022-10-07  thebearmay  Option to use American Standard Login
 *  2026-06-04  Codex        Add Trane Home diagnostics support for newer thermostats
 *  2026-06-05  Codex        Categorize app under Integrations
 *  2026-09-04  Claude       Scrape pages as text - Hubitat can no longer parse
 *                           Trane's HTML into a navigable document
 *  2026-09-04  Claude       serverUrl is the bare origin, not .../login
 *  2026-09-04  Claude       Detect rejected logins via the Location header
 *  2026-09-04  Claude       Defer child initialize() so it does not re-enter
 *                           the app before state is persisted (401)
 *  2026-09-04  Claude       Remove the unused diagnostics-thermostat subsystem
 *  2026-09-04  Claude       Re-authenticate when the session expires - Hubitat
 *                           follows the redirect, so a stale session arrives as
 *                           200 + login HTML, not as a 302
 *  2026-09-04  Claude       Apply the same session check to zone/thermostat
 *                           writes, which silently no-opped when logged out
 *
 */
static String version()	{  return '1.2.0' }

definition(
    name: "Nexia Thermostat Manager",
    namespace: "trentfoley",
    author: "Trent Foley",
    description: "Connect your Nexia thermostat to Hubitat.",
    category: "Convenience",
    menu: "Integrations",
	importUrl:"https://raw.githubusercontent.com/waterboysh/hubitat/main/trane-home-thermostat/nexiaThermostatManager.groovy",    
    iconUrl: "http://lh4.ggpht.com/oMx3-nlICwLmUxpDhTXWsZ6Ocuzu9P2yfz9jpXBx1rhrW_Vcj94kPl2M9ooApckK6TM1=w60",
    iconX2Url: "https://www.trane.com/content/dam/Trane/residential/products/nexia/medium/TR_Nexia%20-%20Medium.jpg",
    iconX3Url: "https://www.trane.com/content/dam/Trane/residential/products/nexia/medium/TR_Nexia%20-%20Medium.jpg",
    singleInstance: true
) { }

preferences {
    section("<h2 style='color:blue'>Nexia Authentication<br><span style='font-size:small'>v${version()}</span></h2>") {
        input "username", "text", title: "Username"
        input "password", "password", title: "Password"
        input "debugEnabled", "bool", title: "Enable debug logging?", width:4
        input "useAmerStand", "bool", title: "Use American Standard login", width:4, defaultValue:false
        if(debugEnabled) runIn(1800, "logsOff")
    }
}

def getChildNamespace() { "trentfoley" }
def getChildName() { "Nexia Thermostat" }
// Base origin only. Every request supplies its own path. The old value ended in
// "/login", which made the pathless dashboard fetch re-read the login page - so
// the /houses/{id}/climate link was never there to find.
def getServerUrl() {
    if(useAmerStand)
        return "https://asairhome.com"
    else
        return "https://www.tranehome.com"
}

def installed() {
    if(debugEnabled) log.debug("installed()")
    initialize()
}

def updated() {
    if(debugEnabled) log.debug("updated()")
    unsubscribe()
    initialize()
}

def initialize() {
    // Unconditional so there is never any doubt about WHICH build is running.
    log.debug("Nexia Thermostat Manager v${version()} initialize()")
    if(debugEnabled) log.debug("initialize()")

    // Ensure authenticated and know where this account's thermostats live
    reauthenticate()

    // Get list of thermostats and ensure child devices
    requestThermostats { thermostatsResp ->
        def devices = []
        if(thermostatsResp.data && thermostatsResp.data.size() > 0) {
            devices = thermostatsResp.data.collect { stat ->
                if(debugEnabled) log.debug("Found thermostat with ID: ${stat.id}")
                
                //Check for Multiple Zones
                def dni = getDeviceNetworkId(stat.id)
                def device = null;
                if(stat.zones.size > 1) {
                    stat.zones.each {
                        dni = getDeviceNetworkId(stat.id + "_" + it.id)
                        device = addMultipleDevices(dni, it.name)
                    }
                }
                else {
                    dni = getDeviceNetworkId(stat.id)
                    device = addMultipleDevices(dni, stat.name)
                }
                return device
            }
        } else {
            log.warn("No thermostats returned from ${state.thermostatsPath}")
        }

        log.info("Discovered ${devices.size()} thermostat(s)")
    }

    // Do NOT call device.initialize() inline. The child's initialize() polls,
    // which re-enters this app through parent.pollChild() in a separate
    // execution context that reloads state from the database. Mid-initialize()
    // nothing has been persisted yet, so the child sees a null thermostatsPath
    // and no session cookies, and the request comes back 401.
    runIn(5, "initializeChildDevices")
}

// Log in and re-derive this account's house paths. Called from initialize() and
// again whenever a request comes back looking unauthenticated.
private boolean reauthenticate() {
    refreshAuthToken()
    return discoverHousePaths()
}

// Find the house id on the dashboard. Scraped as text - see httpGetText().
private boolean discoverHousePaths() {
    def homeBody = httpGetText("/")
    if(!homeBody) {
        log.error("Could not fetch the Trane Home dashboard")
        return false
    }
    if(debugEnabled) log.debug("Dashboard body length=${homeBody.length()}")

    def houseId = matchFirst(homeBody, /\/houses\/(\d+)\/climate/)
    if(!houseId) houseId = matchFirst(homeBody, /\/houses\/(\d+)/)

    if(!houseId) {
        log.error("No /houses/{id} link found on the dashboard - are we actually logged in?")
        return false
    }

    state.thermostatsPath = "/houses/${houseId}/xxl_thermostats"
    state.zonesPath = "/houses/${houseId}/xxl_zones"
    if(debugEnabled) log.debug("Found house ${houseId}")
    return true
}

// Runs after initialize() has ended and state has been persisted.
def initializeChildDevices() {
    def children = getChildDevices()
    if(debugEnabled) log.debug("initializeChildDevices() for ${children.size()} device(s)")

    children.each { device ->
        try { device.initialize() }
        catch(e) { log.error("Failed to initialize ${device.displayName}: ${e}") }
    }
}

private def addMultipleDevices(dni, statname) {
    def device = getChildDevice(dni)
    if(!device) {
        try {
            device = addChildDevice(childNamespace, childName, dni, [ label: "${childName} (${statname})" ])
            if(device) {
                log.info("Created child device ${device.displayName}")
                if(debugEnabled) log.debug("Created ${device.displayName} with device network id: ${dni}")
            } else {
                log.error("Child device creation returned no device for ${statname}")
            }
        }
        catch(e) {
            log.error("Failed to create child device ${statname}: ${e}")
        }
    } else {
        log.info("Child device already exists: ${device.displayName}")
        if(debugEnabled) log.debug("Found already existing ${device.displayName} with device network id: ${dni}")
    }
    return device
}

// ---------------------------------------------------------------------------
// Text scraping. Hubitat cannot parse Trane's HTML into a navigable document -
// resp.data comes back as an object whose children()/size()/getAt() all fail -
// so every page is fetched with textParser:true and read with regexes.
// ---------------------------------------------------------------------------

// Read a response body without naming Reader/InputStream (the sandbox rejects
// those class references). Groovy puts getText() on both, so try it first.
private String readBodyText(o) {
    if(o == null) return null
    if(o instanceof String) return o

    try { return o.getText() }
    catch(e) { /* not a Reader/InputStream, fall through */ }

    try { return o.toString() }
    catch(e) { return null }
}

// First capture group of the first match, or null.
private String matchFirst(String body, String regex) {
    if(!body) return null
    def m = (body =~ regex)
    if(m.find()) return m.group(1)
    return null
}

// GET a page and return its body as text.
private String httpGetText(String path) {
    def params = [
        uri: serverUrl,
        headers: getDefaultHeaders(),
        textParser: true
    ]
    if(path) params.path = path

    def body = null
    try {
        httpGet(params) { resp ->
            if(resp.status == 200) {
                updateCookies(resp)
                body = readBodyText(resp?.data)
            } else {
                log.error("Unexpected status ${resp.status} fetching ${path}")
            }
        }
    }
    catch(e) { log.error("Caught exception fetching ${path}: ${e}") }

    return body
}

private String getDeviceNetworkId(def statId) {
    return [ app.id, statId ].join('.')
}

private updateCookies(response) {
    if(state.cookies == null) state.cookies = [:]
    response.getHeaders('Set-Cookie').each {
        def cookieValue = it.value.split(';')[0]
        def cookieName = cookieValue.split('=')[0]
        state.cookies[(cookieName)] = cookieValue
        if(debugEnabled) log.debug("Cookie updated: ${cookieName}")
    }
}

def getDefaultHeaders() {
    def headers = [
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8',
        'Accept-Encoding': 'gzip, deflate',
        'Accept-Language': 'en-US,en,q=0.8',
        'Cache-Control': 'max-age=0',
        'Connection': 'keep-alive',
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/76.0.3809.100 Safari/537.36',
        'X-CSRF-Token': state.csrfToken,
		'X-Requested-With': 'XMLHttpRequest'
    ]

    def cookieString = state.cookies?.collect { entry -> entry.value }?.join('; ');
    if (cookieString) { headers.Cookie = cookieString }
    return headers
}

private refreshAuthToken() {
    if(debugEnabled) log.debug("refreshAuthToken()")
    if(debugEnabled) log.info("Attempting Trane Home login")

    // Initialize / clear any existing cookies
    state.cookies = [:]

    // Scrape the login form as text. The DOM walk this used to do cannot work:
    // Hubitat fails to parse this page, so resp.data is not navigable.
    def loginBody = httpGetText("/login")
    if(!loginBody) {
        log.error("Could not fetch the login page")
        return
    }
    if(debugEnabled) log.debug("Login page body length=${loginBody.length()}")

    state.AuthToken = matchFirst(loginBody, /name="authenticity_token"[^>]*value="([^"]+)"/)
    state.csrfToken = matchFirst(loginBody, /name="csrf-token"[^>]*content="([^"]+)"/)

    if(!state.AuthToken) {
        log.error("Could not find authenticity_token on the login page")
        return
    }
    if(debugEnabled) log.debug("Authenticity token found; csrfToken present=${state.csrfToken != null}")

    def sessionParams = [
        uri: serverUrl,
        path: '/session',
        requestContentType: 'application/x-www-form-urlencoded',
        headers: getDefaultHeaders(),
        body: [
            'utf8': '✓',
            'authenticity_token': state.AuthToken,
            'login': settings.username,
            'password': settings.password
        ]
    ]

    try {
        httpPost(sessionParams) { sessionResp ->
            updateCookies(sessionResp)

            // A successful login is a 302 to the site root. A 302 back to
            // /login means the credentials were rejected.
            def location = null
            try {
                sessionResp?.getHeaders()?.each { h ->
                    if(h.name?.toLowerCase() == "location") location = h.value
                }
            }
            catch(e) { /* header read is best effort */ }

            if(sessionResp.status == 302 && location != null && location.contains("/login")) {
                log.error("Trane Home rejected the login - check username/password")
            } else if(sessionResp.status == 302 || sessionResp.status == 200) {
                if(debugEnabled) log.debug("Trane Home login successful")
            } else {
                log.error("Unexpected login status ${sessionResp.status}")
            }
        }
    }
    catch(e) {
        log.error("Caught exception posting login ${e}")
    }
}

private requestThermostats(Closure closure) {
    requestThermostatsWithRetry(closure, true)
}

// Session expiry does NOT arrive as a 302. Hubitat follows redirects, so an
// expired session yields 200 with the login page's HTML. That object is not
// navigable, but it does answer find(Closure) with null - which is why a stale
// session used to surface as "No data found ... after polling" rather than as
// an auth error. The content type is the reliable tell: the real endpoint
// answers application/json, the login page answers text/html.
private requestThermostatsWithRetry(Closure closure, boolean allowRetry) {
    if(debugEnabled) log.debug("requestThermostats(${state.thermostatsPath})")

    // A child poll runs in its own execution context; if it never picked up the
    // path, re-derive it rather than GETting the site root and finding nothing.
    if(!state.thermostatsPath) {
        if(!allowRetry) {
            log.error("No thermostatsPath available after re-authenticating")
            return
        }
        if(debugEnabled) log.warn("No thermostatsPath in state; re-authenticating")
        reauthenticate()
        requestThermostatsWithRetry(closure, false)
        return
    }

    def thermostatsParams = [
        uri: serverUrl,
        path: state.thermostatsPath,
        headers: getDefaultHeaders()
    ]

    try {
        httpGet(thermostatsParams) { resp ->
            def isJson = resp.contentType?.toString()?.contains("json")

            if(resp.status == 200 && isJson) {
                closure(resp)
            } else if(allowRetry) {
                if(debugEnabled) log.warn("Thermostat request looks unauthenticated (status ${resp.status}, contentType ${resp.contentType}); re-authenticating")
                reauthenticate()
                requestThermostatsWithRetry(closure, false)
            } else {
                log.error("Still unauthenticated after re-login (status ${resp.status}, contentType ${resp.contentType})")
            }
        }
    }
    catch(e) {
        // An expired session can also come back as a thrown 401.
        if(allowRetry) {
            if(debugEnabled) log.warn("Exception requesting thermostats (${e}); re-authenticating")
            reauthenticate()
            requestThermostatsWithRetry(closure, false)
        } else {
            log.error("Caught exception requesting thermostats ${e}")
        }
    }
}

private requestThermostat(deviceNetworkId, Closure closure) {
    if(debugEnabled) log.debug("requestThermostat(${deviceNetworkId})")
    requestThermostats { resp ->
        def stat = resp.data.find { it -> getDeviceNetworkId(it.id) == deviceNetworkId }
        if (!stat) {
            log.error("Device connection removed? No data found for ${deviceNetworkId} after polling")
        } else {
            closure(stat)
        }
    }
}

// Poll Child is invoked from the Child Device itself as part of the Poll Capability
def pollChild(child) {
    //if zoned, take off zone id... performs a repetitive update due to zoning, fix later
    def deviceNetworkId = ((child.device.deviceNetworkId).split('_'))[0]
    def zonedBool = ((child.device.deviceNetworkId).split('_')).size()
    if(debugEnabled) log.debug("ZoneBool ${zonedBool} pollChild(${deviceNetworkId})")

    def statData = [:]

    requestThermostat(deviceNetworkId) { stat ->
        def zone = stat.zones[0]
        if(zonedBool > 1) {
            def zoneNetworkId = ((child.device.deviceNetworkId).split('_'))[1]
            zone = stat.zones.find {it.id == zoneNetworkId.toInteger()}
        }
        
        def systemStatusToOperatingStateMapping = [
            "System Idle": "idle",
            "Waiting...": "pending ${zone.zone_mode.toLowerCase()}",
            "Heating": "heating",
            "Cooling": "cooling",
            "Fan Running": "fan only"
        ]
        if(debugEnabled) log.debug "Zone: $zone"

        statData = [
            temperature: zone.temperature.toInteger(),
            heatingSetpoint: zone.heating_setpoint.toInteger(),
            coolingSetpoint: zone.cooling_setpoint.toInteger(),
            thermostatSetpoint: ((zone.zone_mode == "COOL") ? zone.cooling_setpoint : zone.heating_setpoint).toInteger(),
            // TODO: handle case for "emergency heat"
            thermostatMode: zone.requested_zone_mode.toLowerCase(), // "auto" "emergency heat" "heat" "off" "cool"
            thermostatFanMode: stat.fan_mode,  // "auto" "on" "circulate"
            thermostatOperatingState: systemStatusToOperatingStateMapping[stat.system_status], // "heating" "idle" "pending cool" "vent economizer" "cooling" "pending heat" "fan only"
            systemStatus: stat.system_status,
            activeMode: zone.zone_mode.toLowerCase(),
            emergencyHeatSupported: stat.emergency_heat_supported,
            humidity: (stat.current_relative_humidity * 100).toInteger(),
            outdoorTemperature: stat.raw_outdoor_temperature.toInteger(),
            setpointStatus: zone.setpoint_status
        ]
    }
    
    return statData
}

// The writes below face the same trap as the reads: an expired session is not
// a 302, it is a 200 carrying the login page, which would otherwise be logged
// as "update suceeded". Only an HTML body is treated as unauthenticated - a
// successful API write never returns HTML, while treating "not JSON" as failure
// would misfire if these endpoints answer with an empty or unusual body.
private boolean looksUnauthenticated(resp) {
    return resp?.contentType?.toString()?.contains("html")
}

// updateType can be: "setpoints", "zone_mode"
private updateZone(zone, updateType) {
    updateZoneWithRetry(zone, updateType, true)
}

private updateZoneWithRetry(zone, updateType, boolean allowRetry) {
    if(debugEnabled) log.debug("updateZone(${zone.id}, ${updateType})")

    zone.hold_time = zone.hold_time.toBigInteger()

    def requestParams = [
        uri: serverUrl,
        path: "${state.zonesPath}/${zone.id}/${updateType}",
        headers: getDefaultHeaders(),
        body: zone
    ]

    try {
        httpPutJson(requestParams) { resp ->
            if (resp.status == 200 && !looksUnauthenticated(resp)) {
                if(debugEnabled) log.debug("Zone update suceeded")
            } else if (allowRetry) {
                if(debugEnabled) log.warn("Zone update looks unauthenticated (status ${resp.status}, contentType ${resp.contentType}); re-authenticating")
                reauthenticate()
                updateZoneWithRetry(zone, updateType, false)
            } else {
                log.error("Unexpected status while attempting to update zone: ${resp.status} (contentType ${resp.contentType})")

                /*
                def zoneJson = new org.json.JSONObject(zone).toString()
                def interations = Math.ceil(zoneJson.length() / 1200.0)
                for(int i = 0; i <= interations; i++) {
                    def end = i * 1200 + 1200
                    if (zoneJson.length() < end) {
                        end = zoneJson.length()
                    }
                    if(debugEnabled) log.debug "${i}: ${zoneJson.substring(i * 1200, end)}"
                }
                */
            }
        }
    }
    catch(e) {
        // Without this the exception escapes into requestThermostats' handler,
        // which would re-run the whole enclosing closure and write twice.
        if(allowRetry) {
            log.warn("Exception updating zone (${e}); re-authenticating")
            reauthenticate()
            updateZoneWithRetry(zone, updateType, false)
        } else {
            log.error("Caught exception updating zone ${e}")
        }
    }
}

// updateType can be: "fan_mode"
private updateThermostat(stat, updateType) {
    updateThermostatWithRetry(stat, updateType, true)
}

private updateThermostatWithRetry(stat, updateType, boolean allowRetry) {
    if(debugEnabled) log.debug("updateThermostat(${stat.id}, ${updateType})")

    def requestParams = [
        uri: serverUrl,
        path: "${state.thermostatsPath}/${stat.id}/${updateType}",
        headers: getDefaultHeaders(),
        body: stat
    ]

    try {
        httpPutJson(requestParams) { resp ->
            if (resp.status == 200 && !looksUnauthenticated(resp)) {
                if(debugEnabled) log.debug("Thermostat update suceeded")
            } else if (allowRetry) {
                if(debugEnabled) log.warn("Thermostat update looks unauthenticated (status ${resp.status}, contentType ${resp.contentType}); re-authenticating")
                reauthenticate()
                updateThermostatWithRetry(stat, updateType, false)
            } else {
                log.error("Unexpected status while attempting to update thermostat: ${resp.status} (contentType ${resp.contentType})")
            }
        }
    }
    catch(e) {
        if(allowRetry) {
            log.warn("Exception updating thermostat (${e}); re-authenticating")
            reauthenticate()
            updateThermostatWithRetry(stat, updateType, false)
        } else {
            log.error("Caught exception updating thermostat ${e}")
        }
    }
}

def setHeatingSetpoint(child, degreesF) {
    def deviceNetworkId = ((child.device.deviceNetworkId).split('_'))[0]
    def zonedBool = ((child.device.deviceNetworkId).split('_')).size()
    if(debugEnabled) log.debug("setHeatingSetpoint(${deviceNetworkId}, ${degreesF})")
    
    requestThermostat(deviceNetworkId) { stat ->
        def zone = stat.zones[0]
        if(zonedBool > 1) {
            def zoneNetworkId = ((child.device.deviceNetworkId).split('_'))[1]
            zone = stat.zones.find {it.id == zoneNetworkId.toInteger()}
        }
        zone.heating_setpoint = degreesF
        zone.heating_integer = "${degreesF.toInteger()}"
        zone.heating_decimal = ""
        zone.cooling_setpoint = zone.cooling_setpoint
        zone.cooling_integer = "${zone.cooling_setpoint}"
        zone.cooling_decimal = ""
        
        updateZone(zone, "setpoints")
    }
}

def setCoolingSetpoint(child, degreesF) {
    def deviceNetworkId = ((child.device.deviceNetworkId).split('_'))[0]
    def zonedBool = ((child.device.deviceNetworkId).split('_')).size()
    if(debugEnabled) log.debug("setCoolingSetpoint(${deviceNetworkId}, ${degreesF})")
    
    requestThermostat(deviceNetworkId) { stat ->
        def zone = stat.zones[0]
        if(zonedBool > 1) {
            def zoneNetworkId = ((child.device.deviceNetworkId).split('_'))[1]
            zone = stat.zones.find {it.id == zoneNetworkId.toInteger()}
        }
        zone.heating_setpoint = zone.heating_setpoint
        zone.heating_integer = "${zone.heating_setpoint.toInteger()}"
        zone.heating_decimal = ""
        zone.cooling_setpoint = degreesF
        zone.cooling_integer = "${degreesF.toInteger()}"
        zone.cooling_decimal = ""
        
        updateZone(zone, "setpoints")
    }
}

def setThermostatMode(child, value) {
    def deviceNetworkId = ((child.device.deviceNetworkId).split('_'))[0]
    def zonedBool = ((child.device.deviceNetworkId).split('_')).size()
    if(debugEnabled) log.debug("setThermostatMode(${deviceNetworkId}, ${value})")
    
    requestThermostat(deviceNetworkId) { stat ->
        def zone = stat.zones[0]
        if(zonedBool > 1) {
            def zoneNetworkId = ((child.device.deviceNetworkId).split('_'))[1]
            zone = stat.zones.find {it.id == zoneNetworkId.toInteger()}
        }
        zone.requested_zone_mode = value.toUpperCase()
        zone.last_requested_zone_mode = value.toUpperCase()
        updateZone(zone, "zone_mode")
    }
}

def setHoldMode(child, value) {//"permanent_hold" or "return_to_schedule"
    def deviceNetworkId = ((child.device.deviceNetworkId).split('_'))[0]
    def zonedBool = ((child.device.deviceNetworkId).split('_')).size()
    if(debugEnabled) log.debug("setThermostatMode(${deviceNetworkId}, ${value})")
    
    requestThermostat(deviceNetworkId) { stat ->
        def zone = stat.zones[0]
        if(zonedBool > 1) {
            def zoneNetworkId = ((child.device.deviceNetworkId).split('_'))[1]
            zone = stat.zones.find {it.id == zoneNetworkId.toInteger()}
        }
        
        updateZone(zone, value)
    }
}

def setThermostatFanMode(child, value) {
    def deviceNetworkId = ((child.device.deviceNetworkId).split('_'))[0]
    def zonedBool = ((child.device.deviceNetworkId).split('_')).size()
    if(debugEnabled) log.debug("setThermostatFanMode(${deviceNetworkId}, ${value})")
    
    requestThermostat(deviceNetworkId) { stat ->
        stat.fan_mode = value
        updateThermostat(stat, "fan_mode")
    }
}

void logsOff(){
    app.updateSetting("debugEnabled",[value:"false",type:"bool"])
}
