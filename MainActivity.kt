package de.wegner.haapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

fun http(method: String, url: String, body: String? = null, headers: Map<String, String> = emptyMap()): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.requestMethod = method; c.connectTimeout = 10000; c.readTimeout = 10000
    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
    if (body != null) {
        c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
    }
    val s = if (c.responseCode < 400) c.inputStream else c.errorStream
    return s.bufferedReader().use { it.readText() }
}

data class Dev(val id: String, val name: String, val online: Boolean,
               val on: Boolean?, val code: String, val watt: Double?)

class Tuya(private val host: String, private val id: String, private val secret: String) {
    private var token = ""
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun hmac(s: String): String {
        val m = Mac.getInstance("HmacSHA256"); m.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return m.doFinal(s.toByteArray()).joinToString("") { "%02X".format(it) }
    }
    private fun call(method: String, path: String, body: String? = null): JSONObject {
        val t = System.currentTimeMillis().toString()
        val sign = hmac(id + token + t + "$method\n${sha(body ?: "")}\n\n$path")
        val h = mutableMapOf("client_id" to id, "sign" to sign, "t" to t, "sign_method" to "HMAC-SHA256")
        if (token.isNotEmpty()) h["access_token"] = token
        val r = JSONObject(http(method, host.trimEnd('/') + path, body, h))
        if (!r.optBoolean("success")) throw Exception(r.optString("msg", "Tuya-Fehler"))
        return r
    }
    fun login() { token = ""; token = call("GET", "/v1.0/token?grant_type=1").getJSONObject("result").getString("access_token") }
    fun devices(uid: String): List<Dev> {
        val arr = call("GET", "/v1.0/users/$uid/devices").getJSONArray("result")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val st = o.optJSONArray("status") ?: JSONArray()
            var code = ""; var on: Boolean? = null; var w: Double? = null
            for (i in 0 until st.length()) {
                val s = st.getJSONObject(i); val c = s.getString("code")
                if (c.startsWith("switch") && code.isEmpty() && s.get("value") is Boolean) { code = c; on = s.getBoolean("value") }
                if (c == "cur_power") w = s.getDouble("value") / 10.0
            }
            Dev(o.getString("id"), o.optString("name"), o.optBoolean("online"), on, code, w)
        }
    }
    fun set(d: Dev, value: Boolean) {
        call("POST", "/v1.0/devices/${d.id}/commands",
            """{"commands":[{"code":"${d.code}","value":$value}]}""")
    }
}

fun wmo(c: Int) = when (c) {
    0 -> "klar"; 1, 2, 3 -> "bewölkt"; 45, 48 -> "Nebel"
    in 51..67 -> "Regen"; in 71..77 -> "Schnee"; in 80..82 -> "Schauer"; in 95..99 -> "Gewitter"; else -> "wechselhaft"
}

fun weatherText(lat: String, lon: String): String {
    val j = JSONObject(http("GET", "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
        "&current=temperature_2m,wind_speed_10m,weather_code&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
        "&timezone=auto&forecast_days=3"))
    val c = j.getJSONObject("current"); val d = j.getJSONObject("daily")
    val sb = StringBuilder("Jetzt: ${c.getDouble("temperature_2m")} °C, ${wmo(c.getInt("weather_code"))}, Wind ${c.getDouble("wind_speed_10m")} km/h\n\n")
    val days = d.getJSONArray("time")
    for (i in 0 until days.length())
        sb.append("${days.getString(i)}: ${d.getJSONArray("temperature_2m_min").getDouble(i)}–${d.getJSONArray("temperature_2m_max").getDouble(i)} °C, " +
            "Regen ${d.getJSONArray("precipitation_probability_max").optInt(i)} %\n")
    return sb.toString()
}

fun loadNews(): List<Pair<String, String>> {
    val x = http("GET", "https://www.tagesschau.de/index~rss2.xml")
    return Regex("<item>.*?<title>(.*?)</title>.*?<link>(.*?)</link>", RegexOption.DOT_MATCHES_ALL).findAll(x).map {
        it.groupValues[1].replace("<![CDATA[", "").replace("]]>", "").trim() to it.groupValues[2].trim()
    }.toList()
}

fun openHatch(ctx: Context): Boolean {
    val pm = ctx.packageManager
    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val a = pm.queryIntentActivities(i, 0).firstOrNull { it.loadLabel(pm).toString().contains("hatch", true) } ?: return false
    val l = pm.getLaunchIntentForPackage(a.activityInfo.packageName) ?: return false
    ctx.startActivity(l); return true
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("ha", Context.MODE_PRIVATE) }
    fun p(k: String, d: String = "") = prefs.getString(k, d) ?: d
    var lat by remember { mutableStateOf(p("lat", "54.43")) }
    var lon by remember { mutableStateOf(p("lon", "13.40")) }
    var host by remember { mutableStateOf(p("host", "https://openapi.tuyaeu.com")) }
    var tid by remember { mutableStateOf(p("tid")) }
    var tsec by remember { mutableStateOf(p("tsec")) }
    var uid by remember { mutableStateOf(p("uid")) }
    var tab by remember { mutableStateOf(0) }
    var weather by remember { mutableStateOf("") }
    var news by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var devs by remember { mutableStateOf(listOf<Dev>()) }
    var msg by remember { mutableStateOf("") }
    var tuya by remember { mutableStateOf<Tuya?>(null) }
    val scope = rememberCoroutineScope()

    fun save() {
        prefs.edit().putString("lat", lat).putString("lon", lon).putString("host", host)
            .putString("tid", tid).putString("tsec", tsec).putString("uid", uid).apply()
    }
    fun run(block: suspend () -> Unit) = scope.launch {
        try { block(); msg = "" } catch (e: Exception) { msg = e.message ?: "Fehler" }
    }
    fun loadWeather() = run { weather = withContext(Dispatchers.IO) { weatherText(lat, lon) } }
    fun loadNewsNow() = run { news = withContext(Dispatchers.IO) { loadNews() } }
    fun loadDevs() = run {
        devs = withContext(Dispatchers.IO) {
            val t = tuya ?: Tuya(host, tid, tsec).also { it.login(); tuya = it }
            t.devices(uid)
        }
    }
    fun toggle(d: Dev) = run {
        withContext(Dispatchers.IO) { tuya!!.set(d, d.on != true) }
        loadDevs()
    }

    LaunchedEffect(Unit) {
        loadWeather(); loadNewsNow()
        if (tid.isNotBlank() && tsec.isNotBlank() && uid.isNotBlank()) loadDevs()
    }

    Scaffold(topBar = {
        Column {
            TopAppBar(title = { Text("Heimzentrale") })
            TabRow(selectedTabIndex = tab) {
                listOf("Wetter", "News", "Geräte", "Einst.").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            when (tab) {
                0 -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(weather); Spacer(Modifier.height(12.dp))
                    Button(onClick = { loadWeather() }) { Text("Aktualisieren") }
                }
                1 -> Column {
                    Button(onClick = { loadNewsNow() }) { Text("Aktualisieren") }
                    LazyColumn {
                        items(news) { (t, l) ->
                            Text(t, Modifier.fillMaxWidth().clickable {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(l)))
                            }.padding(vertical = 8.dp))
                            HorizontalDivider()
                        }
                    }
                }
                2 -> Column {
                    Row {
                        Button(onClick = { loadDevs() }) { Text("Aktualisieren") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { if (!openHatch(ctx)) msg = "Hatch-Solar-App nicht gefunden" }) { Text("Hatch Solar") }
                    }
                    LazyColumn {
                        items(devs) { d ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(d.name)
                                    Text((if (d.online) "online" else "offline") + (d.watt?.let { " · $it W" } ?: ""),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                if (d.on != null) Switch(checked = d.on, enabled = d.online, onCheckedChange = { toggle(d) })
                            }
                            HorizontalDivider()
                        }
                    }
                }
                else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Wetter-Standort (Breite / Länge)")
                    Row { OutlinedTextField(lat, { lat = it }, Modifier.weight(1f)); Spacer(Modifier.width(8.dp)); OutlinedTextField(lon, { lon = it }, Modifier.weight(1f)) }
                    Spacer(Modifier.height(12.dp)); Text("Tuya Cloud (iot.tuya.com)")
                    OutlinedTextField(host, { host = it }, Modifier.fillMaxWidth(), label = { Text("Endpoint") })
                    OutlinedTextField(tid, { tid = it }, Modifier.fillMaxWidth(), label = { Text("Access ID") })
                    OutlinedTextField(tsec, { tsec = it }, Modifier.fillMaxWidth(), label = { Text("Access Secret") })
                    OutlinedTextField(uid, { uid = it }, Modifier.fillMaxWidth(), label = { Text("User UID (aus verknüpftem App-Konto)") })
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { save(); tuya = null; loadWeather(); loadDevs() }) { Text("Speichern & laden") }
                }
            }
            if (msg.isNotBlank()) Text(msg, color = MaterialTheme.colorScheme.error)
        }
    }
}
