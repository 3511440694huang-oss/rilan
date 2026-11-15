package app.rilan.daily.schedule

import android.content.Context
import app.rilan.daily.core.EventKind
import app.rilan.daily.core.Prefs
import app.rilan.daily.core.RepeatRule
import app.rilan.daily.core.ScheduleEvent
import app.rilan.daily.core.newId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val UNTITLED_TITLE = "未命名日程"

/**
 * 日程的唯一数据入口。
 *
 * 内存里维护一份按 startDate、time 升序的快照，每次写操作同时更新 flow 并落盘；
 * 所有写操作都在同一把锁内完成，UI 与 BroadcastReceiver 并发调用不会丢更新。
 */
class ScheduleStore(context: Context) {

    private val prefs = Prefs(context.applicationContext)
    private val lock = Any()

    private val _events = MutableStateFlow<List<ScheduleEvent>>(emptyList())

    /** 当前全部日程，已按 startDate、time 升序排序。 */
    val events: StateFlow<List<ScheduleEvent>> = _events.asStateFlow()

    init {
        _events.value = sortEvents(runCatching { prefs.loadSchedules() }.getOrDefault(emptyList()))
    }

    /**
     * 新增。id 为空或与现有条目撞车时重新生成，返回真正落库的那一份。
     */
    fun add(event: ScheduleEvent): ScheduleEvent = synchronized(lock) {
        val current = _events.value
        val incoming = event.id.trim()
        val id = if (incoming.isEmpty() || current.any { it.id == incoming }) newId() else incoming
        val prepared = normalize(event.copy(id = id))
        persist(current.filterNot { it.id == id } + prepared)
        prepared
    }

    /**
     * 更新。id 不存在时按新增处理，避免把一次编辑悄悄丢掉。
     */
    fun update(event: ScheduleEvent) {
        synchronized(lock) {
            val current = _events.value
            val prepared = normalize(event.copy(id = event.id.trim().ifEmpty { newId() }))
            val index = current.indexOfFirst { it.id == prepared.id }
            persist(
                if (index >= 0) current.toMutableList().also { it[index] = prepared }
                else current + prepared
            )
        }
    }

    fun delete(id: String) {
        synchronized(lock) {
            val current = _events.value
            if (current.none { it.id == id }) return
            persist(current.filterNot { it.id == id })
        }
    }

    /**
     * 切换某一天（yyyy-MM-dd）的完成状态。
     */
    fun toggleDone(id: String, date: String) {
        val day = date.trim()
        if (day.isEmpty()) return
        synchronized(lock) {
            val current = _events.value
            if (current.none { it.id == id }) return
            persist(
                current.map { event ->
                    if (event.id != id) {
                        event
                    } else {
                        val done = if (day in event.doneDates) {
                            event.doneDates.filterNot { it == day }
                        } else {
                            event.doneDates + day
                        }
                        event.copy(doneDates = done)
                    }
                }
            )
        }
    }

    fun get(id: String): ScheduleEvent? = _events.value.firstOrNull { it.id == id }

    fun snapshot(): List<ScheduleEvent> = _events.value

    // ── 内部实现 ────────────────────────────────────────────────────────────

    private fun persist(list: List<ScheduleEvent>) {
        val sorted = sortEvents(list)
        _events.value = sorted
        runCatching { prefs.saveSchedules(sorted) }
    }

    private fun normalize(event: ScheduleEvent): ScheduleEvent = event.copy(
        title = event.title.trim().ifBlank { UNTITLED_TITLE },
        repeat = normalizeRepeat(event),
    )

    /** 生日 / 纪念日业务上默认每年；用户明确选过的其它重复方式保持不变。 */
    private fun normalizeRepeat(event: ScheduleEvent): RepeatRule {
        val yearlyByDefault = event.kind == EventKind.BIRTHDAY || event.kind == EventKind.ANNIVERSARY
        return if (yearlyByDefault && event.repeat == RepeatRule.NONE) RepeatRule.YEARLY else event.repeat
    }

    /** 先按日期、再按时间（没有时间的排前面）；相同时保持原有顺序。 */
    private fun sortEvents(list: List<ScheduleEvent>): List<ScheduleEvent> =
        list.sortedWith(compareBy<ScheduleEvent>({ it.startDate }, { it.time.orEmpty() }))
}
