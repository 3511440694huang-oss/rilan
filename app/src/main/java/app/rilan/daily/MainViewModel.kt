package app.rilan.daily

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.rilan.daily.core.AiDigest
import app.rilan.daily.core.AiTestState
import app.rilan.daily.core.AppSettings
import app.rilan.daily.core.AppRepository
import app.rilan.daily.core.CityResult
import app.rilan.daily.core.HomeState
import app.rilan.daily.core.NewsRecord
import app.rilan.daily.core.ScheduleEvent
import app.rilan.daily.core.SettingsUiState
import app.rilan.daily.core.SummaryMode
import app.rilan.daily.core.SummaryPeriod
import app.rilan.daily.mcp.McpServerState
import app.rilan.daily.mcp.McpTestState
import app.rilan.daily.plugins.SourcePlugin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo: AppRepository = (application as RilanApp).repo

    val home: StateFlow<HomeState> = repo.home
    val schedules: StateFlow<List<ScheduleEvent>> = repo.schedules
    val records: StateFlow<List<NewsRecord>> = repo.records
    val digests: StateFlow<Map<SummaryPeriod, AiDigest>> = repo.digests
    /** DeepSeek 直连正在生成的周期；null 表示空闲。 */
    val summaryLoading: StateFlow<SummaryPeriod?> = repo.summaryLoading
    val cityResults = repo.cityResults
    val mcpState: StateFlow<McpServerState> = repo.mcpState
    val mcpTest: StateFlow<McpTestState> = repo.mcpTest
    val plugins: StateFlow<List<SourcePlugin>> = repo.plugins
    val pluginErrors: StateFlow<List<String>> = repo.pluginErrors
    val pluginDirectory: String get() = repo.pluginDirectory
    val aiTest: StateFlow<AiTestState> = repo.aiTest

    private val _settingsUi = MutableStateFlow(SettingsUiState(settings = repo.settings.value))
    val settingsUi: StateFlow<SettingsUiState> = _settingsUi.asStateFlow()

    init {
        viewModelScope.launch {
            repo.settings.collect { s -> _settingsUi.update { it.copy(settings = s) } }
        }
        viewModelScope.launch {
            repo.cityResults.collect { results -> _settingsUi.update { it.copy(cityResults = results) } }
        }
        viewModelScope.launch {
            repo.citySearching.collect { searching -> _settingsUi.update { it.copy(citySearching = searching) } }
        }
        repo.bootstrap()
    }

    fun onForeground() = repo.onForeground()

    fun ensureMcpRunningIfEnabled() = repo.ensureMcpRunningIfEnabled()

    fun refreshAll() = repo.refreshAll()

    fun locateNow() = repo.locateNow()

    fun setAutoLocation(enabled: Boolean) = repo.setAutoLocation(enabled)

    fun markLocationAsked() = repo.markLocationAsked()

    fun saveSettings(settings: AppSettings) {
        repo.saveSettings(settings)
    }

    fun searchCity(query: String) = repo.searchCity(query)

    fun chooseCity(result: CityResult) = repo.chooseCity(result)

    fun clearAiContent() = repo.clearAiCaches()

    fun generateDailyBrief() = repo.generateDailyBrief()

    fun setSummaryMode(mode: SummaryMode) = repo.setSummaryMode(mode)

    /** 周期总结：用 DeepSeek API Key 直连生成，写回与 MCP 相同的 digest。 */
    fun generatePeriodSummaryDirect(period: SummaryPeriod) =
        repo.generatePeriodSummaryDirect(period)

    fun testDeepSeek(apiKey: String, model: String) = repo.testDeepSeek(apiKey, model)

    fun startMcp() {
        repo.startMcp()
    }

    fun stopMcp() = repo.stopMcp()

    fun testMcp() = repo.testMcp()

    fun savePlugin(rawJson: String, originalId: String? = null): String? =
        repo.savePlugin(rawJson, originalId)

    fun deletePlugin(id: String) = repo.deletePlugin(id)

    fun togglePlugin(id: String, enabled: Boolean) = repo.togglePlugin(id, enabled)

    fun setHotListEnabled(category: app.rilan.daily.core.Category, enabled: Boolean) =
        repo.setHotListEnabled(category, enabled)

    fun reloadPlugins() = repo.reloadPlugins()

    fun addSchedule(event: ScheduleEvent) = repo.addSchedule(event)

    fun updateSchedule(event: ScheduleEvent) = repo.updateSchedule(event)

    fun deleteSchedule(id: String) = repo.deleteScheduleEvent(id)

    fun toggleScheduleDone(id: String, date: String) = repo.toggleScheduleDone(id, date)

    fun sendTestNotification() = repo.sendTestNotification()

    fun notificationsEnabled(): Boolean =
        NotificationManagerCompat.from(getApplication()).areNotificationsEnabled()
}
