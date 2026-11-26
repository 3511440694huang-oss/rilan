package app.rilan.daily.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 冷启动网络预热：App 刚起来时在后台把天气相关域名先解析一遍，
 * 让紧随其后的第一个天气请求不必把 DNS 解析算进关键路径。
 *
 * 为什么需要它：部分机型 / 运营商的双栈环境会优先返回 AAAA 地址，而 IPv6 实际不可达；
 * 自写的 HttpURLConnection 封装没有 Happy Eyeballs，只能一个地址一个地址地试，
 * 于是「解析 + 先试 v6 卡满 connectTimeout」就成了冷启动天气慢的第一步。
 * 这里提前把结果灌进系统 DNS 缓存，并把 v4 / v6 的可达性写进日志，
 * 出问题时能一眼判断是不是「这台机器 v6 不通」。
 *
 * 约定：
 * - 只做「解析 + 一次很短的 TCP 探测」，不下载内容、不读用户数据；
 * - 全部 runCatching，绝不抛异常；[warm] 只投递后台任务，调用方立刻返回。
 */
object NetWarmup {

    private const val TAG = "NetWarmup"
    private const val PROBE_PORT = 443
    private const val PROBE_TIMEOUT_MS = 800

    /** 天气与城市搜索用到的域名；顺序即解析顺序，主源放第一个。 */
    private val HOSTS = listOf(
        "api.open-meteo.com",
        "geocoding-api.open-meteo.com",
        "wttr.in",
        "api.met.no",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 一个进程只预热一次：多窗口 / 多次回前台不重复占网络。 */
    @Volatile private var started = false

    /** 最近一次预热结论，仅供排查（不参与业务逻辑）。 */
    @Volatile var lastReport: String = ""
        private set

    fun warm() {
        if (started) return
        started = true
        scope.launch {
            runCatching { warmHosts() }
        }
    }

    private fun warmHosts() {
        var resolved = 0
        var v4 = 0
        var v6 = 0
        for (host in HOSTS) {
            val addresses = runCatching { InetAddress.getAllByName(host) }
                .getOrNull()
                .orEmpty()
            if (addresses.isEmpty()) continue
            resolved++
            addresses.forEach { address ->
                when (address) {
                    is Inet4Address -> v4++
                    is Inet6Address -> v6++
                }
            }
            // 只探测主源：开太多探测连接反而会在弱网里和真正的天气请求抢带宽，
            // 这里只要知道「这台机器上 v4 / v6 哪条路通」就够了。
            if (host == HOSTS.first()) {
                addresses.forEach { address ->
                    val family = if (address is Inet6Address) "IPv6" else "IPv4"
                    val reachable = runCatching {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(address, PROBE_PORT), PROBE_TIMEOUT_MS)
                        }
                        true
                    }.getOrDefault(false)
                    Log.i(TAG, "$host [$family ${address.hostAddress}] ${if (reachable) "可达" else "不可达"}")
                }
            }
        }
        lastReport = "resolved=$resolved/${HOSTS.size} v4=$v4 v6=$v6"
        Log.i(TAG, "预热完成：$lastReport")
    }
}
