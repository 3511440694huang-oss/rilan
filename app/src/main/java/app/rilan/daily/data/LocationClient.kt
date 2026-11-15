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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

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
 * - 优先用 2 分钟内的系统缓存位置；没有就请求一次当前位置；
 * - 请求失败时退回 24 小时内的旧位置，至少让天气还能按已知经纬度更新；
 * - 反向地理编码先走 BigDataCloud 中文数据，拿到城市、区县和街道；
 * - 区县缺失时再短暂尝试系统 Geocoder，补上街道门牌；
 * - 全部失败时抛 IOException，由仓库展示「定位失败」，不会悄悄用北京。
 */
class LocationClient(context: Context) {

    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun currentPlace(): PlaceInfo = withContext(Dispatchers.IO) {
        if (!hasPermission()) throw SecurityException("没有定位权限")
        val now = System.currentTimeMillis()
        val lastKnown = lastKnownLocation()
        val location = lastKnown?.takeIf { now - it.time <= 2 * 60_000L }
            ?: requestCurrentLocation()
            ?: lastKnown?.takeIf { now - it.time <= 24 * 60 * 60_000L }
            ?: throw IOException("无法获取当前位置，请到室外或打开网络定位后重试")

        val named = reverseGeocode(location.latitude, location.longitude)
        PlaceInfo(
            city = named.city.ifBlank { "当前位置" },
            district = named.district,
            street = named.street,
            latitude = location.latitude,
            longitude = location.longitude,
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

        return withTimeoutOrNull(9_000L) {
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

    private suspend fun reverseGeocode(latitude: Double, longitude: Double): PlaceName {
        val big = bigDataCloud(latitude, longitude)
        if (big != null && big.district.isNotBlank() && big.street.isNotBlank()) return big

        // 系统 Geocoder 在部分机型上又慢又可能阻塞，只给 2.5 秒用它补区县 / 街道。
        val system = withTimeoutOrNull(2_500L) { systemGeocoder(latitude, longitude) }
        return merge(big, system) ?: PlaceName(city = "当前位置")
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
}
