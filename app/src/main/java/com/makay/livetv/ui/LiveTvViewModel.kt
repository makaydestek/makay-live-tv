package com.makay.livetv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.makay.livetv.data.AppSettingsStore
import com.makay.livetv.domain.model.Channel
import com.makay.livetv.domain.model.ChannelFilter
import com.makay.livetv.domain.model.ChannelSort
import com.makay.livetv.domain.model.ChannelType
import com.makay.livetv.domain.model.Program
import com.makay.livetv.domain.model.TimerEntry
import com.makay.livetv.domain.model.TimerResult
import com.makay.livetv.domain.model.TimerStatus
import com.makay.livetv.domain.model.TimerType
import com.makay.livetv.domain.model.TunerAvailability
import com.makay.livetv.epg.ChannelEpg
import com.makay.livetv.epg.EpgRepository
import com.makay.livetv.tuner.ChannelPreferences
import com.makay.livetv.tuner.ChannelRepository
import com.makay.livetv.tuner.TvInputDiscovery
import com.makay.livetv.recording.RecordedProgram
import com.makay.livetv.recording.RecordedProgramRepository
import com.makay.livetv.recording.RecordingState
import com.makay.livetv.parental.ParentalControlStore
import com.makay.livetv.timer.TimerScheduler
import com.makay.livetv.timer.TimerStore
import com.makay.livetv.util.SafeLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Canli TV ekraninin tum durumunu yonetir.
 *
 * Sorumluluklar
 * -------------
 *  - Kanal listesini TvProvider'dan yuklemek ve degisiklikleri izlemek
 *  - Favori/son izlenen tercihlerini kanallara uygulamak
 *  - EPG "şimdi / sonraki" bilgisini dakikada bir tazelemek
 *  - Aktif kanali, filtreleri ve arama sorgusunu tutmak
 */
class LiveTvViewModel(application: Application) : AndroidViewModel(application) {

    private val discovery = TvInputDiscovery(application)
    private val channelsRepo = ChannelRepository(application, discovery)
    private val prefs = ChannelPreferences.getInstance(application)
    private val epgRepo = EpgRepository(application)
    private val timerStore = TimerStore.getInstance(application)
    private val timerScheduler = TimerScheduler.getInstance(application)
    private val recordedProgramRepository = RecordedProgramRepository(application)
    private val parentalStore = ParentalControlStore(application)
    val settings: AppSettingsStore = AppSettingsStore.getInstance(application)

    // ---- Ham veri ----
    private val _allChannels = MutableStateFlow<List<Channel>>(emptyList())
    private val _availability = MutableStateFlow<TunerAvailability>(
        TunerAvailability.NotSupported
    )
    val availability: StateFlow<TunerAvailability> = _availability.asStateFlow()

    // ---- Gorunum durumu ----
    private val _activeChannel = MutableStateFlow<Channel?>(null)
    val activeChannel: StateFlow<Channel?> = _activeChannel.asStateFlow()

    private val _filter = MutableStateFlow(ChannelFilter.ALL)
    val filter: StateFlow<ChannelFilter> = _filter.asStateFlow()

    private val _sort = MutableStateFlow(ChannelSort.NUMBER)
    val sort: StateFlow<ChannelSort> = _sort.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _epg = MutableStateFlow<Map<Long, ChannelEpg>>(emptyMap())
    val epg: StateFlow<Map<Long, ChannelEpg>> = _epg.asStateFlow()

    val timers: StateFlow<List<TimerEntry>> = timerStore.timers.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    val isRecording: StateFlow<Boolean> = RecordingState.recording
    val recordingError: StateFlow<String?> = RecordingState.error
    private val _maxAllowedAge = MutableStateFlow(parentalStore.maxAllowedAge)
    val maxAllowedAge: StateFlow<Int?> = _maxAllowedAge.asStateFlow()
    private val _recordings = MutableStateFlow<List<RecordedProgram>>(emptyList())
    val recordings: StateFlow<List<RecordedProgram>> = _recordings.asStateFlow()

    /**
     * Sayi tuslarindan girilen kanal numarasi tamponu (orn. "1", "12", "101").
     * Bosken gizlenir; doluyken ekranda gosterilir ve su sure sonra tune edilir.
     */
    private val _numberBuffer = MutableStateFlow("")
    val numberBuffer: StateFlow<String> = _numberBuffer.asStateFlow()

    private var numberJob: Job? = null

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** Filtre + siralama + aramadan gecmis, gosterime hazir kanal listesi. */
    val visibleChannels: StateFlow<List<Channel>> =
        combine(_allChannels, _filter, _sort, _query) { all, filter, sort, query ->
            filterChannels(all, filter, sort, query)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private var epgJob: Job? = null
    private var observeJob: Job? = null
    private var recentChannelJob: Job? = null

    init {
        observeJob = viewModelScope.launch {
            var initialSnapshotReceived = false
            channelsRepo.observeChannels().collect { raw ->
                val initial = if (!initialSnapshotReceived && raw.isEmpty()) {
                    loadInitialChannelsWithRetry()
                } else {
                    raw
                }
                initialSnapshotReceived = true
                val decorated = parentalStore.applyTo(prefs.applyTo(initial))
                _allChannels.value = decorated
                refreshAvailability()
                restoreSelectionIfNeeded(decorated)
                _isLoading.value = false
                SafeLog.d(TAG, "Kanallar guncellendi: ${decorated.size}")
            }
        }
        startEpgTicker()
        viewModelScope.launch { refreshRecordings() }
    }

    // ---------------------------------------------------------------- kanal secimi

    /**
     * Kanal secer. Gecersiz kimlik veya ayni kanal icin islem yapmaz
     * (gereksiz tune = yayin kesintisi olur).
     */
    fun selectChannel(channel: Channel) {
        if (_activeChannel.value?.id == channel.id) return
        _activeChannel.value = channel
        // Kumandadan gelen hızlı zap dizilerinde her ara kanal için DataStore
        // yazma kuyruğu oluşturma; ekrandaki seçim hemen değişsin, yalnızca
        // kullanıcının son kanalını kısa bir duraklamadan sonra kaydet.
        recentChannelJob?.cancel()
        recentChannelJob = viewModelScope.launch {
            delay(RECENT_CHANNEL_SAVE_DEBOUNCE_MS)
            runCatching { prefs.markWatched(channel) }
                .onFailure { SafeLog.w(TAG, "markWatched", it) }
        }
    }

    /**
     * Kimligine gore kanal arar. Zamanlayici olaylari icin kullanilir;
     * gorunur liste filtrelenmis olsa bile (sakli/ari kanal) bulur.
     */
    fun channelById(id: Long): Channel? = _allChannels.value.firstOrNull { it.id == id }

    /** Sırali listede bir sonraki/onceki kanala gecer (DPAD yukari/asagi). */
    fun zap(delta: Int) {
        val list = visibleChannels.value
        if (list.isEmpty()) {
            SafeLog.w(TAG, "Zap yok sayildi: kanal listesi bos")
            return
        }
        val current = _activeChannel.value
        val idx = list.indexOfFirst { it.id == current?.id }
        var nextIdx = when {
            idx < 0 -> if (delta >= 0) 0 else list.lastIndex
            else -> (idx + delta).mod(list.size)
        }
        // Skip isaretli kanallari yonunde gec; liste tamamen skip ise
        // ilk adimda kal (kullanici yine de bir kanala gecer).
        val firstCandidate = nextIdx
        while (list[nextIdx].isLocallySkipped) {
            nextIdx = (nextIdx + delta).mod(list.size)
            if (nextIdx == firstCandidate) break
        }
        SafeLog.d(TAG, "Zap delta=$delta current=${current?.id} next=${list[nextIdx].id} (${list[nextIdx].displayName})")
        selectChannel(list[nextIdx])
    }

    /** Kanal numarasindan dogrudan tune (sayi tusu). */
    fun zapByNumber(number: String): Boolean {
        val normalized = number.trim().trimStart('0').ifEmpty { "0" }
        val match = _allChannels.value.firstOrNull { channel ->
            if (channel.isLocallyDeleted) return@firstOrNull false
            val display = channel.displayNumber.trim().replace(',', '.')
            val exact = display.trimStart('0').ifEmpty { "0" } == normalized
            val integerEquivalent = display.toDoubleOrNull()?.let { value ->
                value >= 0 && value % 1.0 == 0.0 && value.toLong().toString() == normalized
            } == true
            exact || integerEquivalent
        } ?: return false
        selectChannel(match)
        return true
    }

    /**
     * Sayi tusuna basilir: tampona ekler, [NUMBER_ENTRY_TIMEOUT_MS] sonra
     * tune eder. Boylece "12" yazmak once 1'e atlamaz; tampon sure doldugunda
     * (veya en fazla [MAX_NUMBER_LENGTH] haneye ulastiginda) tam sayi tune edilir.
     */
    fun inputDigit(digit: String) {
        if (digit.isEmpty() || _numberBuffer.value.length >= MAX_NUMBER_LENGTH) return
        _numberBuffer.value += digit
        numberJob?.cancel()
        numberJob = viewModelScope.launch {
            delay(NUMBER_ENTRY_TIMEOUT_MS)
            commitNumberEntry()
        }
    }

    /**
     * Tamponu hemen tune eder. Bulunamazsa tamponu temizler (sessizce).
     * OK tusuyla da cagrilabilir: tampon doluysa beklemeden tune.
     * @return tune edildiyse true.
     */
    fun commitNumberEntry(): Boolean {
        numberJob?.cancel()
        numberJob = null
        val entry = _numberBuffer.value
        if (entry.isEmpty()) return false
        _numberBuffer.value = ""
        val tuned = zapByNumber(entry)
        if (!tuned) SafeLog.d(TAG, "Kanal numarasi bulunamadi: $entry")
        return tuned
    }

    /** Tamponu iptal eder (Geri tusu / baska etkilesim). */
    fun cancelNumberEntry() {
        numberJob?.cancel()
        numberJob = null
        _numberBuffer.value = ""
    }

    // ---------------------------------------------------------------- filtreler

    fun setFilter(filter: ChannelFilter) {
        _filter.value = filter
    }

    fun cycleFilter() {
        val values = ChannelFilter.entries
        _filter.value = values[(values.indexOf(_filter.value) + 1) % values.size]
    }

    fun setSort(sort: ChannelSort) {
        _sort.value = sort
    }

    fun cycleSort() {
        val values = ChannelSort.entries
        _sort.value = values[(values.indexOf(_sort.value) + 1) % values.size]
    }

    fun setQuery(query: String) {
        _query.value = query
    }

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch {
            runCatching {
                prefs.toggleFavorite(channel)
                agentReload()
            }.onFailure { SafeLog.w(TAG, "toggleFavorite", it) }
        }
    }

    fun setChannelHidden(channel: Channel, hidden: Boolean) {
        viewModelScope.launch {
            runCatching {
                prefs.setHidden(channel, hidden)
                agentReload()
            }.onFailure { SafeLog.w(TAG, "setChannelHidden", it) }
        }
    }

    /** Kanali yerel listelerden siler (TvProvider'a dokunulmaz; Bakim'dan geri gelir). */
    fun setChannelDeleted(channel: Channel, deleted: Boolean) {
        viewModelScope.launch {
            runCatching {
                prefs.setDeleted(channel, deleted)
                agentReload()
            }.onFailure { SafeLog.w(TAG, "setChannelDeleted", it) }
        }
    }

    /** CH+/- zap'ta atlanacak kanal isareti. */
    fun setChannelSkipped(channel: Channel, skipped: Boolean) {
        viewModelScope.launch {
            runCatching {
                prefs.setSkipped(channel, skipped)
                agentReload()
            }.onFailure { SafeLog.w(TAG, "setChannelSkipped", it) }
        }
    }

    /** Gizli + silinen tum kanallari listeye geri getirir (Bakim eylemi). */
    fun restoreHiddenAndDeleted() {
        viewModelScope.launch {
            runCatching {
                prefs.restoreHiddenAndDeleted()
                agentReload()
            }.onFailure { SafeLog.w(TAG, "restoreHiddenAndDeleted", it) }
        }
    }

    /** Kanal kişiselleştirmelerini kullanıcı tarafından seçilen dosyaya yazar. */
    suspend fun exportChannelPreferences(uri: android.net.Uri): Boolean = runCatching {
        val json = prefs.exportBackup()
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { output ->
                output.writer(Charsets.UTF_8).use { it.write(json) }
                true
            } ?: false
        }
    }.onFailure { SafeLog.w(TAG, "Kanal tercihleri yedeklenemedi", it) }.getOrDefault(false)

    /** Seçilen JSON yedeğini doğrular ve yalnız uygulama içi kanal tercihlerini yükler. */
    suspend fun importChannelPreferences(uri: android.net.Uri): Boolean = runCatching {
        val json = withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(8_192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (result.length + count > MAX_CHANNEL_BACKUP_CHARS) return@withContext null
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
        } ?: return false
        if (!prefs.importBackup(json)) return false
        agentReload()
        true
    }.onFailure { SafeLog.w(TAG, "Kanal tercihleri yedeği yüklenemedi", it) }.getOrDefault(false)

    fun moveChannel(channel: Channel, delta: Int) {
        viewModelScope.launch {
            runCatching {
                prefs.move(channel, delta, _allChannels.value)
                agentReload()
                _sort.value = ChannelSort.CUSTOM
                _filter.value = ChannelFilter.ALL
            }.onFailure { SafeLog.w(TAG, "moveChannel", it) }
        }
    }

    /** KanaI adi yalnizca bu uygulamada gorunecek sekilde ozellestirilir. */
    fun renameChannel(channel: Channel, name: String?) {
        viewModelScope.launch {
            runCatching {
                prefs.rename(channel, name)
                agentReload()
            }.onFailure { SafeLog.w(TAG, "renameChannel", it) }
        }
    }

    private suspend fun agentReload() {
        val fresh = parentalStore.applyTo(prefs.applyTo(channelsRepo.loadChannels()))
        _allChannels.value = fresh
    }

    fun hasParentalPin(): Boolean = parentalStore.hasPin
    fun setParentalPin(pin: String): Boolean = parentalStore.setPin(pin)
    fun clearParentalPin(pin: String): Boolean {
        val cleared = parentalStore.clearPin(pin)
        if (cleared) {
            _maxAllowedAge.value = null
            _menuLockEnabled.value = false
        }
        return cleared
    }

    private val _menuLockEnabled = MutableStateFlow(parentalStore.menuLockEnabled)
    val menuLockEnabled: StateFlow<Boolean> = _menuLockEnabled.asStateFlow()

    /** Menu kilidi; yalnizca ebeveyn PIN'i varken acilabilir. */
    fun setMenuLock(enabled: Boolean): Boolean {
        val saved = parentalStore.setMenuLock(enabled)
        if (saved) _menuLockEnabled.value = parentalStore.menuLockEnabled
        return saved
    }
    fun verifyParentalPin(pin: String): Boolean = parentalStore.verify(pin)
    fun isRatingBlocked(program: Program): Boolean = parentalStore.requiresPin(program)
    fun setMaxAllowedAge(age: Int?): Boolean {
        val saved = parentalStore.setMaxAllowedAge(age)
        if (saved) _maxAllowedAge.value = age
        return saved
    }
    fun updateParentalPin(currentPin: String, newPin: String): Boolean =
        (!parentalStore.hasPin || parentalStore.verify(currentPin)) && parentalStore.setPin(newPin)

    fun setChannelLocked(channel: Channel, locked: Boolean): Boolean {
        val saved = parentalStore.setLocked(channel, locked)
        if (saved) viewModelScope.launch { agentReload() }
        return saved
    }

    // ---------------------------------------------------------------- EPG

    private fun startEpgTicker() {
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            while (isActive) {
                // The ticker starts before the first TvProvider channel
                // snapshot. Wait for channels instead of sleeping a full
                // minute and leaving the initial guide/banner empty.
                if (_allChannels.value.isEmpty()) {
                    _allChannels.first { it.isNotEmpty() }
                }
                refreshEpg()
                delay(EPG_REFRESH_INTERVAL_MS)
            }
        }
    }

    suspend fun refreshEpg() {
        val list = _allChannels.value
        if (list.isEmpty()) {
            _epg.value = emptyMap()
            return
        }
        runCatching {
            val now = System.currentTimeMillis()
            val map = epgRepo.nowAndNext(list, now)
            _epg.value = map
        }.onFailure { SafeLog.w(TAG, "refreshEpg", it) }
    }

    /** Belirli bir kanalin gunluk akisi (EPG ekrani icin). */
    suspend fun dayTimeline(channel: Channel, dayStartUtc: Long, dayEndUtc: Long) =
        epgRepo.dayTimeline(channel, dayStartUtc, dayEndUtc)

    /**
     * Secili gun icin tum kanallarin programlari (tam EPG izgarasi).
     * Tek sorguda okunur; sonuc `channelId` → program listesi.
     */
    suspend fun programsInRange(
        channels: List<Channel>,
        fromUtcMillis: Long,
        toUtcMillis: Long,
    ): Map<Long, List<Program>> = epgRepo.programsInRange(channels, fromUtcMillis, toUtcMillis)

    /** EPG programi icin hatirlatma kurar; mevcut kayit varsa tekrar eklemez. */
    suspend fun scheduleReminder(channel: Channel, program: Program, repeatWeekly: Boolean = false): TimerResult {
        if (program.endTimeUtcMillis <= System.currentTimeMillis()) return TimerResult.ENDED
        val entry = timerStore.newEntry(channel, program, TimerType.REMIND, repeatWeekly)
        val result = timerStore.add(entry)
        if (result == TimerResult.ADDED) timerScheduler.schedule(entry)
        return result
    }

    /** TV girdisi destekliyorsa EPG programi icin kayit alarmi kurar. */
    suspend fun scheduleRecording(channel: Channel, program: Program, repeatWeekly: Boolean = false): TimerResult {
        if (!canRecord(channel)) return TimerResult.NOT_SUPPORTED
        if (program.endTimeUtcMillis <= System.currentTimeMillis()) return TimerResult.ENDED
        // Ayni girdide cakisan kayit: iki TvRecordingClient oturumu ayni anda
        // baslamaz — ikinciyi daha once reddet.
        val conflict = timerStore.all().any {
            it.status == TimerStatus.SCHEDULED && it.type == TimerType.RECORD &&
                it.inputId == channel.inputId &&
                it.startUtcMillis < program.endTimeUtcMillis &&
                program.startTimeUtcMillis < it.endUtcMillis
        }
        if (conflict) return TimerResult.CONFLICT
        val entry = timerStore.newEntry(channel, program, TimerType.RECORD, repeatWeekly)
        val result = timerStore.add(entry)
        if (result == TimerResult.ADDED) timerScheduler.schedule(entry)
        return result
    }

    /** Hatirlatmayi kalici kayittan ve AlarmManager'dan kaldirir. */
    fun cancelReminder(timerId: Long) {
        viewModelScope.launch {
            timerScheduler.cancel(timerId)
            timerStore.remove(timerId)
        }
    }

    // ---------------------------------------------------------------- durum

    private fun refreshAvailability() {
        _availability.value = when {
            !discovery.isTvInputAvailable -> TunerAvailability.NotSupported
            _allChannels.value.isEmpty() -> {
                val inputs = discovery.discoverInputs()
                if (inputs.isEmpty()) TunerAvailability.NotSupported
                else TunerAvailability.NeedsScan(inputs)
            }
            else -> TunerAvailability.Ready(
                discovery.discoverInputs(),
                _allChannels.value.size,
            )
        }
    }

    /**
     * Uygulama acilirken son kanali (veya ilk kanali) sec.
     * Kullanici zaten bir kanal sectiyse dokunulmaz.
     */
    private suspend fun restoreSelectionIfNeeded(channels: List<Channel>) {
        if (_activeChannel.value != null || channels.isEmpty()) return
        val startLast = settings.startWithLastChannel.first()
        val lastId = if (startLast) prefs.lastChannelId.first() else null
        val lastKey = if (startLast) prefs.lastChannelKey.first() else null
        val target = lastId?.let { id -> channels.firstOrNull { it.id == id } }
            ?: lastKey?.let { key -> channels.firstOrNull { ChannelPreferences.favoriteKeyOf(it) == key } }
            ?: sortChannels(channels).firstOrNull()
        if (target != null && _activeChannel.value == null) {
            _activeChannel.value = target
        }
    }

    /** TvProvider ilk sorguda henüz boş dönebiliyor; boş ekranı erken gösterme. */
    private suspend fun loadInitialChannelsWithRetry(): List<Channel> {
        repeat(INITIAL_CHANNEL_RETRY_COUNT) { attempt ->
            delay(INITIAL_CHANNEL_RETRY_DELAY_MS)
            val fresh = channelsRepo.loadChannels()
            if (fresh.isNotEmpty()) return fresh
            SafeLog.d(TAG, "İlk kanal sorgusu boş (${attempt + 1}/$INITIAL_CHANNEL_RETRY_COUNT)")
        }
        return emptyList()
    }

    /** Ilk kurulum tamamlandi. */
    fun completeFirstLaunch() = settings.setFirstLaunchDone(true)

    /** Kurulumu Activity Result akisi ile acmak icin gereken uretici intent'i. */
    fun tunerSetupIntent(): android.content.Intent? = discovery.createSetupIntent()

    /** Uretici tarama ekranindan donunce TV provider verisini yeniler. */
    fun refreshChannelsFromProvider() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // DTVKit bazen tarama ekranindan hemen donuste bos snapshot verir.
                // Acilisla ayni kisa tekrar sorgularini kullanarak sahte "kanal yok"
                // durumunu ve secim ekranindaki bosluk/flicker'i engelle.
                val raw = loadInitialChannelsWithRetry()
                val fresh = parentalStore.applyTo(prefs.applyTo(raw))
                _allChannels.value = fresh
                refreshAvailability()
                restoreSelectionIfNeeded(fresh)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** Cihazdaki secili TV girdisinin tarama/kurulum ekranini acar. */
    fun openTunerSetup(): Boolean {
        return launchTunerIntent(listOfNotNull(discovery.createSetupIntent(), android.content.Intent("android.settings.TV_INPUTS")))
    }

    fun openTunerSettings(): Boolean = launchTunerIntent(
        listOfNotNull(
            discovery.createInputSettingsIntent(),
            discovery.createSetupIntent(),
            android.content.Intent("android.settings.TV_INPUTS"),
        ),
    )

    private fun launchTunerIntent(intents: List<android.content.Intent>): Boolean {
        val app = getApplication<Application>()
        for (intent in intents) {
            val opened = runCatching {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(intent)
                true
            }.onFailure { SafeLog.w(TAG, "DTV kurulum ekrani baslatilamadi", it) }
                .getOrDefault(false)
            if (opened) return true
        }
        return false
    }

    fun canRecord(channel: Channel?): Boolean =
        channel != null && discovery.getInput(channel.inputId)?.supportsRecord == true

    fun startRecording(channel: Channel?, programId: Long? = null): Boolean {
        channel ?: return false
        if (!canRecord(channel) || isRecording.value) return false
        return runCatching {
            RecordingState.start(getApplication(), channel.inputId, channel.id, programId)
            true
        }.onFailure { SafeLog.w(TAG, "startRecording", it) }.getOrDefault(false)
    }

    fun stopRecording() = RecordingState.stop(getApplication())

    suspend fun refreshRecordings() {
        _recordings.value = recordedProgramRepository.load()
    }

    fun deleteRecording(id: Long) {
        viewModelScope.launch {
            if (recordedProgramRepository.delete(id)) refreshRecordings()
        }
    }

    /** Son izlenenler listesini ve son kanal kaydini siler. */
    fun clearRecentlyWatched() {
        viewModelScope.launch {
            runCatching { prefs.clearRecent() }
                .onFailure { SafeLog.w(TAG, "clearRecent", it) }
        }
    }

    override fun onCleared() {
        epgJob?.cancel()
        observeJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val TAG = "LiveTvViewModel"
        private const val EPG_REFRESH_INTERVAL_MS = 60_000L

        /** Kanal numarasi tamponu bekleme suresi (TV'lerde yaygin deger). */
        private const val NUMBER_ENTRY_TIMEOUT_MS = 1_900L

        /** Kanal numarasi tamponunun en fazla hane sayisi. */
        private const val MAX_NUMBER_LENGTH = 5
        private const val INITIAL_CHANNEL_RETRY_COUNT = 3
        private const val INITIAL_CHANNEL_RETRY_DELAY_MS = 900L
        private const val RECENT_CHANNEL_SAVE_DEBOUNCE_MS = 700L
        private const val MAX_CHANNEL_BACKUP_CHARS = 1_000_000

        /**
         * Filtre + arama + siralama uygular.
         * Saf fonksiyon: birim testlerde dogrudan cagrilabilir.
         */
        fun filterChannels(
            channels: List<Channel>,
            filter: ChannelFilter,
            sort: ChannelSort,
            query: String,
        ): List<Channel> {
            val q = query.trim().lowercase()
            val filtered = channels.asSequence()
                // Yerel silme: her gorunumden (HIDDEN dahil) kaldirilir;
                // geri getirme Ayarlar > Bakim uzerindendir.
                .filter { !it.isLocallyDeleted }
                .filter { if (filter == ChannelFilter.HIDDEN) it.isLocallyHidden else it.isBrowsable && !it.isLocallyHidden }
                .filter { filter.matches(it) }
                .filter { ch ->
                    q.isEmpty() || ch.searchKey.contains(q)
                }
            return sortChannels(filtered.toList(), sort)
        }

        fun sortChannels(channels: List<Channel>, sort: ChannelSort = ChannelSort.NUMBER): List<Channel> =
            when (sort) {
                ChannelSort.NUMBER -> channels.sortedWith(
                    compareBy({ it.sortKey }, { it.displayName })
                )
                ChannelSort.NAME -> channels.sortedWith(
                    compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
                )
                ChannelSort.CUSTOM -> channels.sortedWith(
                    compareBy<Channel> { it.sortPosition }.thenBy { it.sortKey }.thenBy { it.displayName }
                )
            }

        /** Kanal turune gore varsayilan akilli siralama: TV → radyo → diger. */
        fun groupOf(channel: Channel): Int = when {
            channel.type.isPhysicalTuner && !channel.isRadio -> 0
            channel.isRadio -> 1
            channel.type == ChannelType.PASSTHROUGH -> 2
            else -> 3
        }
    }
}
