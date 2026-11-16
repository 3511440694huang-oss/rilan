package app.rilan.daily.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs

/** 定位结果：城市、区县和街道分开保存，天气卡可以显示到区县级。 */
data class PlaceInfo(
    val city: String,
    val district: String,
    val street: String,
    val latitude: Double,
    val longitude: Double,
) {
    val label: String
        get() = listOf(city, district, street)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" · ")
}

private data class PlaceName(
    val city: String = "",
    val district: String = "",
    val street: String = "",
)

/**
 * 系统定位 + 反向地理编码。
 *
 * 速度策略（定位只负责天气，不该让天气等它）：
 * - 只把「坐标」放在关键路径上：2 分钟内的系统缓存位置 → 现取一次（最多 6 秒）→ 24 小时内的旧位置；
 * - 区县 / 街道优先读本地缓存（同一点位 24 小时内有效），命中时直接返回，天气立刻开始请求；
 * - 没有缓存时，关键路径只等 BigDataCloud 2.6 秒；系统 Geocoder 在部分机型上会卡好几秒，
 *   一律丢到后台补全并写入缓存，下次刷新就能直接复用；
 * - 坐标拿不到才抛 IOException，由仓库展示「定位失败」，不会悄悄用北京。
 */
class LocationClient(context: Context) {

    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /** 正向地理编码结果的本地缓存：同一点位 24 小时内不必重新请求。 */
    private val cachePrefs by lazy {
        app.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
    }

    /** 后台补全区县 / 街道用的作用域：不阻塞天气，也不跟随页面取消。 */
    private val refineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun currentPlace(): PlaceInfo = withContext(Dispatchers.IO) {
        if (!hasPermission()) throw SecurityException("没有定位权限")
        val now = System.currentTimeMillis()
        val lastKnown = lastKnownLocation()
        val location = lastKnown?.takeIf { now - it.time <= LAST_KNOWN_FRESH_MS }
            ?: requestCurrentLocation()
            ?: lastKnown?.takeIf { now - it.time <= LAST_KNOWN_MAX_AGE_MS }
            ?: throw IOException("无法获取当前位置，请到室外或打开网络定位后重试")

        val latitude = location.latitude
        val longitude = location.longitude

        // 1. 有缓存就先返回：天气可以立刻用这组坐标开始请求，区县 / 街道只影响一行文案。
        val cached = loadCachedPlace(latitude, longitude)
        if (cached != null) {
            return@withContext PlaceInfo(
                city = cached.city.ifBlank { "当前位置" },
                district = cached.district,
                street = cached.street,
                latitude = latitude,
                longitude = longitude,
            )
        }

        // 2. 没有缓存：关键路径最多等 2.6 秒；正常网络 300–800ms 就会回来。
        val named = withTimeoutOrNull(NAME_CRITICAL_TIMEOUT_MS) {
            runCatching { bigDataCloud(latitude, longitude) }.getOrNull()
        }
        if (named != null) saveCachedPlace(latitude, longitude, named)
        if (named == null || named.district.isBlank() || named.street.isBlank()) {
            // 3. 缺区县 / 街道时后台继续补，补到后写入缓存，下次刷新直接命中。
            refineNamesInBackground(latitude, longitude)
        }
        PlaceInfo(
            city = named?.city?.ifBlank { "当前位置" } ?: "当前位置",
            district = named?.district.orEmpty(),
            street = named?.street.orEmpty(),
            latitude = latitude,
            longitude = longitude,
        )
    }

    // ── 系统位置 ────────────────────────────────────────────────────────────

    private fun lastKnownLocation(): Location? {
        val lm = manager ?: return null
        if (!hasPermission()) return null
        val providers = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        return providers.mapNotNull { provider ->
            runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
    }

    private suspend fun requestCurrentLocation(): Location? {
        val lm = manager ?: return null
        if (!hasPermission()) return null
        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return null
        }

        // 6 秒拿不到就先用手上的旧位置，别让天气一直等 GPS 冷启动。
        return withTimeoutOrNull(LOCATE_TIMEOUT_MS) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                suspendCancellableCoroutine { continuation ->
                    runCatching {
                        lm.getCurrentLocation(
                            provider,
                            null,
                            ContextCompat.getMainExecutor(app),
                        ) { location ->
                            if (continuation.isActive) continuation.resume(location)
                        }
                    }.onFailure {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            } else {
                suspendCancellableCoroutine { continuation ->
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (continuation.isActive) continuation.resume(location)
                        }

                        override fun onProviderDisabled(provider: String) {
                            if (continuation.isActive) continuation.resume(null)
                        }

                        override fun onProviderEnabled(provider: String) = Unit

                        @Deprecated("Deprecated in API 29")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                    }
                    runCatching {
                        lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                    }.onFailure {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }
        }
    }

    // ── 反向地理编码 ────────────────────────────────────────────────────────

    /** 关键路径已经返回后再补区县 / 街道：BigDataCloud 慢就再试系统 Geocoder。 */
    private fun refineNamesInBackground(latitude: Double, longitude: Double) {
        refineScope.launch {
            val big = runCatching { bigDataCloud(latitude, longitude) }.getOrNull()
            var merged: PlaceName? = big
            if (big == null || big.district.isBlank() || big.street.isBlank()) {
                val system = withTimeoutOrNull(SYSTEM_GEOCODE_TIMEOUT_MS) {
                    runCatching { systemGeocoder(latitude, longitude) }.getOrNull()
                }
                merged = merge(big, system)
            }
            if (merged != null) saveCachedPlace(latitude, longitude, merged)
        }
    }

    // ── 区县 / 街道缓存 ────────────────────────────────────────────────────

    private fun loadCachedPlace(latitude: Double, longitude: Double): PlaceName? {
        val raw = cachePrefs.getString(CACHE_KEY, null) ?: return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val savedAt = root.optLong("savedAt", 0L)
        if (System.currentTimeMillis() - savedAt > CACHE_TTL_MS) return null
        val cachedLat = root.optDouble("latitude", Double.NaN)
        val cachedLng = root.optDouble("longitude", Double.NaN)
        if (!cachedLat.isFinite() || !cachedLng.isFinite()) return null
        if (abs(cachedLat - latitude) > CACHE_MAX_DISTANCE_DEG) return null
        if (abs(cachedLng - longitude) > CACHE_MAX_DISTANCE_DEG) return null
        val place = PlaceName(
            city = root.optString("city").trim(),
            district = root.optString("district").trim(),
            street = root.optString("street").trim(),
        )
        return place.takeIf { it.city.isNotBlank() || it.district.isNotBlank() }
    }

    private fun saveCachedPlace(latitude: Double, longitude: Double, place: PlaceName) {
        if (place.city.isBlank() && place.district.isBlank()) return
        runCatching {
            val root = JSONObject()
                .put("city", place.city)
                .put("district", place.district)
                .put("street", place.street)
                .put("latitude", latitude)
                .put("longitude", longitude)
                .put("savedAt", System.currentTimeMillis())
            cachePrefs.edit().putString(CACHE_KEY, root.toString()).apply()
        }
    }

    private suspend fun bigDataCloud(latitude: Double, longitude: Double): PlaceName? {
        val url = "https://api.bigdatacloud.net/data/reverse-geocode-client" +
            "?latitude=${String.format(Locale.US, "%.5f", latitude)}" +
            "&longitude=${String.format(Locale.US, "%.5f", longitude)}" +
            "&localityLanguage=zh"
        val body = runCatching {
            Http.getText(
                url,
                headers = mapOf("Accept" to "application/json"),
                connectTimeoutMs = 4_000,
                readTimeoutMs = 5_000,
            )
        }.getOrNull() ?: return null
        return runCatching {
            val root = JSONObject(body)
            val city = cleanCity(root.optString("city"))
                .ifBlank { cleanCity(root.optString("principalSubdivision")) }
                .ifBlank { cleanCity(root.optString("locality")) }

            val adminNames = ArrayList<String>()
            val admin = root.optJSONObject("localityInfo")?.optJSONArray("administrative")
            if (admin != null) {
                for (i in 0 until admin.length()) {
                    val item = admin.optJSONObject(i) ?: continue
                    val name = item.optString("name").trim()
                    if (name.isNotBlank()) adminNames.add(name)
                }
            }

            val locality = root.optString("locality").trim()
                .ifBlank { root.optString("village").trim() }
                .ifBlank { root.optString("suburb").trim() }

            val district = pickDistrict(city, locality, adminNames)
            val street = pickStreet(city, district, locality, adminNames)
            if (city.isBlank() && district.isBlank()) null
            else PlaceName(city = city, district = district, street = street)
        }.getOrNull()
    }

    private suspend fun systemGeocoder(latitude: Double, longitude: Double): PlaceName? =
        withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent()) return@withContext null
            @Suppress("DEPRECATION")
            val address = runCatching {
                Geocoder(app, Locale.SIMPLIFIED_CHINESE)
                    .getFromLocation(latitude, longitude, 1)
                    ?.firstOrNull()
            }.getOrNull() ?: return@withContext null

            val city = cleanCity(address.locality)
                .ifBlank { cleanCity(address.subAdminArea) }
                .ifBlank { cleanCity(address.adminArea) }
            val district = listOfNotNull(address.subLocality, address.subAdminArea)
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() && it != city }
                .orEmpty()
            val street = listOfNotNull(address.thoroughfare, address.subThoroughfare)
                .joinToString("") { it.trim() }
                .ifBlank { address.featureName.orEmpty().trim() }
                .takeIf { it != city && it != district }
                .orEmpty()
            if (city.isBlank() && district.isBlank()) null
            else PlaceName(city = city, district = district, street = street)
        }

    private fun merge(primary: PlaceName?, fallback: PlaceName?): PlaceName? {
        if (primary == null) return fallback
        if (fallback == null) return primary
        return primary.copy(
            city = primary.city.ifBlank { fallback.city },
            district = primary.district.ifBlank { fallback.district },
            street = primary.street.ifBlank { fallback.street },
        )
    }

    private fun pickDistrict(city: String, locality: String, names: List<String>): String {
        // 先找标准的区 / 县 / 旗 / 县级市；「市」可能和地级市重名，必须排除 city。
        names.firstOrNull { name ->
            name != city && (
                name.endsWith("区") ||
                    name.endsWith("县") ||
                    name.endsWith("旗") ||
                    name.endsWith("新区") ||
                    name.endsWith("开发区")
                )
        }?.let { return it }

        if (locality.isNotBlank() && locality != city && !locality.endsWith("省")) {
            return locality
        }
        return ""
    }

    private fun pickStreet(
        city: String,
        district: String,
        locality: String,
        names: List<String>,
    ): String {
        val candidates = buildList {
            add(locality)
            addAll(names)
        }
        return candidates.firstOrNull { name ->
            name.isNotBlank() &&
                name != city &&
                name != district &&
                (
                    name.endsWith("街道") ||
                        name.endsWith("镇") ||
                        name.endsWith("乡") ||
                        name.endsWith("路") ||
                        name.endsWith("巷") ||
                        name.endsWith("社区") ||
                        name.endsWith("村")
                    )
        }.orEmpty()
    }

    private fun cleanCity(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        return value
            .removeSuffix("市")
            .removeSuffix("特别行政区")
            .removeSuffix("自治区")
            .trim()
    }

    private companion object {
        const val CACHE_PREFS = "rilan_location_cache"
        const val CACHE_KEY = "place_v1"
        const val CACHE_TTL_MS = 24 * 60 * 60_000L

        /** 约 2 公里；同一区的缓存可以直接复用。 */
        const val CACHE_MAX_DISTANCE_DEG = 0.02

        const val LAST_KNOWN_FRESH_MS = 2 * 60_000L
        const val LAST_KNOWN_MAX_AGE_MS = 24 * 60 * 60_000L

        /** 现取坐标最多等 6 秒；再久就先用手上的旧位置。 */
        const val LOCATE_TIMEOUT_MS = 6_000L

        /** 关键路径上给反向地理编码的时间：正常 300–800ms，慢网也要在 2.6 秒内放弃。 */
        const val NAME_CRITICAL_TIMEOUT_MS = 2_600L

        /** 后台补系统 Geocoder 的时限。 */
        const val SYSTEM_GEOCODE_TIMEOUT_MS = 2_500L
    }
}
