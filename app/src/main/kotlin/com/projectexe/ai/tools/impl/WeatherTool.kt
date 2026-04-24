package com.projectexe.ai.tools.impl

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.projectexe.ai.tools.Tool
import com.projectexe.ai.tools.ToolDescriptor
import com.projectexe.ai.tools.ToolResult
import com.projectexe.util.UserPreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WeatherTool(
    private val ctx: Context,
    private val prefs: UserPreferenceManager,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "get_weather",
        description = "Get the current weather for a place. Pass a city name OR latitude+longitude. " +
            "If neither is given, uses the user's last known location.",
        parametersJson = """
            {"type":"object","properties":{
              "place":{"type":"string","description":"City or place name."},
              "lat":{"type":"number"},"lon":{"type":"number"}
            },"additionalProperties":false}
        """.trimIndent()
    )

    override suspend fun execute(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val (lat, lon, label) = resolveCoords(args) ?: return@withContext ToolResult.err(
            "No location available. Pass `place` or grant Location permission.",
            "I need a place name or location permission to check the weather.")

        try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                      "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m" +
                      "&temperature_unit=celsius&wind_speed_unit=kmh&timezone=auto"
            val resp = http.newCall(Request.Builder().url(url).build()).execute()
            resp.use { r ->
                if (!r.isSuccessful) return@withContext ToolResult.err("HTTP ${r.code}",
                    "Couldn't reach the weather service.")
                val j = JSONObject(r.body?.string() ?: "{}").optJSONObject("current")
                    ?: return@withContext ToolResult.err("Bad response")
                val temp = j.optDouble("temperature_2m", Double.NaN)
                val rh   = j.optDouble("relative_humidity_2m", Double.NaN)
                val ws   = j.optDouble("wind_speed_10m", Double.NaN)
                val code = j.optInt("weather_code", -1)
                val cond = wmoCodeToString(code)
                ToolResult.ok("$label — $cond, ${"%.0f".format(temp)}°C, wind ${"%.0f".format(ws)} km/h.") {
                    put("location", label); put("condition", cond)
                    put("temp_c", temp); put("humidity_pct", rh); put("wind_kmh", ws)
                }
            }
        } catch (e: Exception) {
            ToolResult.err(e.message ?: "network error", "Couldn't fetch the weather.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun resolveCoords(args: JSONObject): Triple<Double, Double, String>? {
        val place = args.optString("place").takeIf { it.isNotBlank() }
        if (args.has("lat") && args.has("lon")) {
            val lat = args.optDouble("lat"); val lon = args.optDouble("lon")
            if (!lat.isNaN() && !lon.isNaN())
                return Triple(lat, lon, place ?: "%.3f, %.3f".format(lat, lon))
        }
        if (place != null) {
            // Geocode via open-meteo geocoder.
            try {
                val resp = http.newCall(Request.Builder().url(
                    "https://geocoding-api.open-meteo.com/v1/search?count=1&name=" +
                    java.net.URLEncoder.encode(place, "UTF-8")).build()).execute()
                resp.use { r ->
                    if (r.isSuccessful) {
                        val arr = JSONObject(r.body?.string() ?: "{}").optJSONArray("results")
                        val o = arr?.optJSONObject(0)
                        if (o != null) {
                            val name = listOfNotNull(o.optString("name").takeIf { it.isNotBlank() },
                                                     o.optString("country").takeIf { it.isNotBlank() })
                                .joinToString(", ")
                            return Triple(o.getDouble("latitude"), o.getDouble("longitude"), name.ifEmpty { place })
                        }
                    }
                }
            } catch (_: Exception) { /* fall through */ }
        }
        // Cached / last-known
        if (prefs.hasWeatherCoords())
            return Triple(prefs.weatherLat.toDouble(), prefs.weatherLon.toDouble(), "your location")

        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            val lm = ctx.getSystemService(LocationManager::class.java)
            val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            for (p in providers) {
                try {
                    val loc = lm?.getLastKnownLocation(p) ?: continue
                    prefs.weatherLat = loc.latitude.toFloat()
                    prefs.weatherLon = loc.longitude.toFloat()
                    return Triple(loc.latitude, loc.longitude, "your location")
                } catch (_: SecurityException) { /* skip */ }
            }
        }
        return null
    }

    private fun wmoCodeToString(c: Int): String = when (c) {
        0 -> "clear sky"; 1, 2 -> "mostly clear"; 3 -> "overcast"
        45, 48 -> "fog"; in 51..57 -> "drizzle"; in 61..67 -> "rain"
        in 71..77 -> "snow"; in 80..82 -> "rain showers"; in 85..86 -> "snow showers"
        95 -> "thunderstorm"; 96, 99 -> "thunderstorm with hail"
        else -> "unknown conditions"
    }
}
