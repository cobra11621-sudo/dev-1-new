package com.cobra.dev1new

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.cobra.dev1new.data.ApiKeyVault
import com.cobra.dev1new.data.SubwayScheduleClient
import com.cobra.dev1new.domain.ArrivalFormatter
import com.cobra.dev1new.domain.JourneyDefinition
import com.cobra.dev1new.domain.JourneyId
import com.cobra.dev1new.domain.KtxSchedule
import com.cobra.dev1new.domain.KtxScheduleResolver
import com.cobra.dev1new.domain.LegPhase
import com.cobra.dev1new.domain.LegProgress
import com.cobra.dev1new.domain.RouteCatalog
import com.cobra.dev1new.domain.SubwayTripSelection
import com.cobra.dev1new.domain.TravelSnapshot
import com.cobra.dev1new.domain.TransitionResult
import com.cobra.dev1new.domain.TransportKind
import com.cobra.dev1new.service.TransitTrackingService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private val TransitCanvas = Color(0xFFF4F6FA)
private val TransitIndigo = Color(0xFF3949AB)
private val TransitColors = lightColorScheme(
    primary = TransitIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8EDFF),
    onPrimaryContainer = Color(0xFF243577),
    secondary = Color(0xFFE58A2B),
    onSecondary = Color.White,
    background = TransitCanvas,
    onBackground = Color(0xFF20263A),
    surface = Color.White,
    onSurface = Color(0xFF20263A),
    surfaceVariant = Color(0xFFEEF1F6),
    onSurfaceVariant = Color(0xFF596174),
    outline = Color(0xFFD9DFEA),
    error = Color(0xFFB3261E)
)

class MainActivity : ComponentActivity() {
    private val repository by lazy { (application as CommuteApplication).travelRepository }
    private val apiKeyVault by lazy { ApiKeyVault(this) }
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingJourneyId: JourneyId? = null
    private var restorePermissionWarningShown = false

    private val accessLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val fineGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val notificationsGranted = Build.VERSION.SDK_INT < 33 ||
            grants[Manifest.permission.POST_NOTIFICATIONS] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val requested = pendingJourneyId
        pendingJourneyId = null
        if (!fineGranted || !notificationsGranted) {
            val missing = buildString {
                if (!fineGranted) append("정확한 위치 권한")
                if (!fineGranted && !notificationsGranted) append("과 ")
                if (!notificationsGranted) append("알림 권한")
            }
            showPermissionHelp("화면을 꺼도 GPS와 이동 알림을 갱신하려면 ${missing}이 필요합니다. 위치 추적은 여정이 활성화된 동안에만 실행합니다.")
            return@registerForActivityResult
        }
        requested?.let(::beginJourney)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = TransitColors) {
                val snapshot by repository.state.collectAsState()
                NativeCommuteApp(
                    snapshot = snapshot,
                    apiKeyConfigured = apiKeyVault.isConfigured(),
                    onStart = ::requestAccessAndStart,
                    onCancel = {
                        repository.cancelPlan(System.currentTimeMillis())
                        stopService(Intent(this, TransitTrackingService::class.java))
                    },
                    onBoardSubway = ::boardSubway,
                    onSaveSettings = { commute, returning, apiKey ->
                        val keySaved = apiKey == null || runCatching {
                            apiKeyVault.save(apiKey)
                            apiKeyVault.isConfigured() == apiKey.isNotBlank()
                        }.getOrDefault(false)
                        if (keySaved) {
                            commute?.let { repository.saveKtxSchedule(JourneyId.COMMUTE, it, System.currentTimeMillis()) }
                            returning?.let { repository.saveKtxSchedule(JourneyId.RETURN, it, System.currentTimeMillis()) }
                        }
                        keySaved
                    }
                )
            }
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        if (repository.snapshot().activeProgress() == null) {
            restorePermissionWarningShown = false
            return
        }
        val locationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val notificationsGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (locationGranted && notificationsGranted) {
            restorePermissionWarningShown = false
            runCatching { TransitTrackingService.start(this) }.onFailure {
                Toast.makeText(this, "저장된 이동 서비스 재개에 실패했습니다.", Toast.LENGTH_LONG).show()
            }
        } else if (!restorePermissionWarningShown) {
            restorePermissionWarningShown = true
            showPermissionHelp("저장된 여정이 있습니다. 화면을 꺼도 추적을 재개하려면 위치와 알림 권한을 허용해 주세요.")
        }
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun requestAccessAndStart(journeyId: JourneyId) {
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val notificationsGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (fineGranted && notificationsGranted) {
            beginJourney(journeyId)
            return
        }
        pendingJourneyId = journeyId
        val permissions = buildList {
            if (!fineGranted) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 33 && !notificationsGranted) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
        accessLauncher.launch(permissions)
    }

    private fun beginJourney(journeyId: JourneyId) {
        val result = repository.startInitialPlan(journeyId, System.currentTimeMillis())
        if (result !is TransitionResult.Applied) {
            Toast.makeText(this, (result as TransitionResult.Rejected).reason, Toast.LENGTH_LONG).show()
            return
        }
        try {
            TransitTrackingService.start(this)
        } catch (error: Exception) {
            repository.cancelPlan(System.currentTimeMillis())
            Toast.makeText(this, "백그라운드 이동 서비스를 시작하지 못했습니다: ${error.message.orEmpty()}", Toast.LENGTH_LONG).show()
        }
    }

    private fun boardSubway(journeyId: JourneyId, onDone: (String) -> Unit) {
        val tappedAt = System.currentTimeMillis()
        uiScope.launch {
            onDone("전체 시간표에서 탭 시각과 가장 가까운 열차를 찾는 중…")
            try {
                val selected: SubwayTripSelection = SubwayScheduleClient(applicationContext).selectNearestTrip(journeyId, tappedAt)
                val result = repository.manualBoardSubway(selected, tappedAt)
                if (result is TransitionResult.Applied) {
                    TransitTrackingService.start(this@MainActivity)
                    onDone("${KtxScheduleResolver.displayClock(selected.departureEpochMillis)} 열차를 고정했습니다. 이 열차 시간표만 계속 추적합니다.")
                } else onDone((result as TransitionResult.Rejected).reason)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onDone(error.message ?: "지하철 시간표를 불러오지 못했습니다.")
            }
        }
    }

    private fun showPermissionHelp(message: String) {
        AlertDialog.Builder(this)
            .setTitle("이동 추적 권한이 필요합니다")
            .setMessage(message)
            .setPositiveButton("앱 설정") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("닫기", null)
            .show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NativeCommuteApp(
    snapshot: TravelSnapshot,
    apiKeyConfigured: Boolean,
    onStart: (JourneyId) -> Unit,
    onCancel: () -> Unit,
    onBoardSubway: (JourneyId, (String) -> Unit) -> Unit,
    onSaveSettings: (KtxSchedule?, KtxSchedule?, String?) -> Boolean
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var boardingJourney by remember { mutableStateOf<JourneyId?>(null) }
    var boardingMessageJourney by remember { mutableStateOf<JourneyId?>(null) }
    var boardingMessage by rememberSaveable { mutableStateOf("") }
    var apiKeyConfiguredState by remember(apiKeyConfigured) { mutableStateOf(apiKeyConfigured) }
    val titles = listOf("출근", "퇴근", "병원")
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("출퇴근 이동도우미", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("버스 · 지하철 · KTX 이동 현황", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {
                        TextButton(onClick = { settingsOpen = true }) {
                            Text("설정", fontWeight = FontWeight.SemiBold)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = TransitCanvas)
                )
                PrimaryTabRow(selectedTabIndex = tab, containerColor = TransitCanvas, contentColor = TransitIndigo) {
                    titles.forEachIndexed { index, title ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index },
                            text = { Text(title, fontWeight = if (tab == index) FontWeight.Bold else FontWeight.Medium) }
                        )
                    }
                }
            }
        }
    ) { insets ->
        Surface(Modifier.fillMaxSize().padding(insets), color = TransitCanvas) {
            when (tab) {
                0 -> JourneyScreen(
                    snapshot, RouteCatalog.commute, onStart, onCancel, boardingJourney,
                    if (boardingMessageJourney == JourneyId.COMMUTE) boardingMessage else "",
                    apiKeyConfiguredState, { settingsOpen = true }
                ) { id ->
                    boardingJourney = id
                    boardingMessageJourney = id
                    boardingMessage = ""
                    onBoardSubway(id) { boardingMessage = it; if (!it.contains("찾는 중")) boardingJourney = null }
                }
                1 -> JourneyScreen(
                    snapshot, RouteCatalog.returning, onStart, onCancel, boardingJourney,
                    if (boardingMessageJourney == JourneyId.RETURN) boardingMessage else "",
                    apiKeyConfiguredState, { settingsOpen = true }
                ) { id ->
                    boardingJourney = id
                    boardingMessageJourney = id
                    boardingMessage = ""
                    onBoardSubway(id) { boardingMessage = it; if (!it.contains("찾는 중")) boardingJourney = null }
                }
                else -> HospitalScreen(snapshot, onStart, onCancel, apiKeyConfiguredState) { settingsOpen = true }
            }
        }
    }
    if (settingsOpen) {
        KtxSettingsDialog(
            initialCommute = snapshot.ktxSchedules[JourneyId.COMMUTE] ?: KtxSchedule(),
            initialReturn = snapshot.ktxSchedules[JourneyId.RETURN] ?: KtxSchedule(),
            initialApiKeyConfigured = apiKeyConfiguredState,
            onDismiss = { settingsOpen = false },
            onSave = { commute, returning, apiKey ->
                val saved = onSaveSettings(commute, returning, apiKey)
                if (saved) {
                    if (apiKey != null) apiKeyConfiguredState = apiKey.isNotBlank()
                    settingsOpen = false
                }
                saved
            }
        )
    }
}

@Composable
private fun JourneyScreen(
    snapshot: TravelSnapshot,
    journey: JourneyDefinition,
    onStart: (JourneyId) -> Unit,
    onCancel: () -> Unit,
    boardingJourney: JourneyId?,
    boardingMessage: String,
    apiKeyConfigured: Boolean,
    onOpenSettings: () -> Unit,
    startBoarding: (JourneyId) -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!apiKeyConfigured) item { ApiKeyNotice(onOpenSettings) }
        item {
            val progress = snapshot.journeys[journey.id]
            RouteCard(
                snapshot, journey, progress,
                active = snapshot.activeJourneyId == journey.id,
                anyActive = snapshot.activeJourneyId != null,
                boarding = boardingJourney == journey.id,
                boardingMessage = boardingMessage,
                onStart = { onStart(journey.id) }, onCancel = onCancel,
                onBoardSubway = { startBoarding(journey.id) }
            )
        }
    }
}

@Composable
private fun HospitalScreen(
    snapshot: TravelSnapshot,
    onStart: (JourneyId) -> Unit,
    onCancel: () -> Unit,
    apiKeyConfigured: Boolean,
    onOpenSettings: () -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("병원 경로는 방향별로 따로 관리", color = TransitIndigo, fontWeight = FontWeight.Bold)
                    Text(
                        "처음 탑승예정만 직접 시작하면 해당 방향 안에서 자동 탑승·하차합니다. 한쪽 완료로 반대 방향은 시작되지 않습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (!apiKeyConfigured) item { ApiKeyNotice(onOpenSettings) }
        items(listOf(RouteCatalog.hospitalOutbound, RouteCatalog.hospitalReturn)) { journey ->
            RouteCard(
                snapshot, journey, snapshot.journeys[journey.id],
                active = snapshot.activeJourneyId == journey.id,
                anyActive = snapshot.activeJourneyId != null,
                boarding = false, boardingMessage = "", onStart = { onStart(journey.id) }, onCancel = onCancel, onBoardSubway = {}
            )
        }
    }
}

@Composable
private fun RouteCard(
    snapshot: TravelSnapshot,
    journey: JourneyDefinition,
    phaseProgress: LegProgress?,
    active: Boolean,
    anyActive: Boolean,
    boarding: Boolean,
    boardingMessage: String,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onBoardSubway: () -> Unit
) {
    val progress = phaseProgress ?: LegProgress(journey.id, 0)
    val currentIndex = if (active) progress.legIndex else -1
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        JourneyOverviewCard(journey, active, anyActive)
        journey.legs.forEachIndexed { index, leg ->
            val isCurrent = index == currentIndex
            val phase = when {
                isCurrent -> progress.phase
                active && index < currentIndex -> LegPhase.COMPLETE
                !active && phaseProgress?.phase == LegPhase.COMPLETE && index <= phaseProgress.legIndex -> LegPhase.COMPLETE
                !active && phaseProgress != null && index < phaseProgress.legIndex -> LegPhase.COMPLETE
                else -> LegPhase.READY
            }
            val cardColor = when {
                isCurrent && phase == LegPhase.ONBOARD -> Color(0xFFFFF1DE)
                isCurrent && phase == LegPhase.PLANNED -> Color(0xFFEAF0FF)
                phase == LegPhase.COMPLETE -> Color(0xFFEAF5EB)
                else -> Color(0xFFF0F2F6)
            }
            val stateBackground = when (phase) {
                LegPhase.ONBOARD -> Color(0xFFFFE1B8)
                LegPhase.PLANNED -> Color(0xFFDCE6FF)
                LegPhase.COMPLETE -> Color(0xFFD8EDDA)
                LegPhase.READY -> Color(0xFFE3E7EF)
            }
            val stateForeground = when (phase) {
                LegPhase.ONBOARD -> Color(0xFF874B00)
                LegPhase.PLANNED -> Color(0xFF263F92)
                LegPhase.COMPLETE -> Color(0xFF286139)
                LegPhase.READY -> Color(0xFF596174)
            }
            val kindLabel = when (leg.kind) {
                TransportKind.BUS -> "버스"
                TransportKind.KTX -> "KTX"
                TransportKind.SUBWAY -> "1호선"
            }
            val kindTint = when (leg.kind) {
                TransportKind.BUS -> Color(0xFFE2E8FF)
                TransportKind.KTX -> Color(0xFFE0EDF4)
                TransportKind.SUBWAY -> Color(0xFFDDF3EF)
            }
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = cardColor),
                elevation = CardDefaults.cardElevation(defaultElevation = if (isCurrent) 2.dp else 1.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(color = kindTint, shape = RoundedCornerShape(9.dp)) {
                            Text(kindLabel, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = TransitIndigo, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                        Text(leg.displayName, Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Surface(color = stateBackground, shape = RoundedCornerShape(50)) {
                            Text(
                                when (phase) { LegPhase.ONBOARD -> "이동 중"; LegPhase.PLANNED -> "탑승예정"; LegPhase.COMPLETE -> "완료"; LegPhase.READY -> "예정" },
                                Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                color = stateForeground,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Text("${leg.origin}  →  ${leg.destination}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        leg.stops.forEachIndexed { stopIndex, stop ->
                            if (stopIndex > 0) Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                            Surface(color = Color.White.copy(alpha = 0.78f), shape = RoundedCornerShape(50)) {
                                Text(stop, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                    if (isCurrent && phase == LegPhase.PLANNED) {
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.9f))) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                when (leg.kind) {
                                    TransportKind.BUS -> {
                                        Text("유효 GPS로 노선 이동을 확인한 뒤 자동 탑승합니다", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (progress.arrivalFetchedAtEpochMillis != null) Text(
                                            ArrivalFormatter.formatPlanned(progress.arrivalVehicles, progress.arrivalFetchedAtEpochMillis),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TransitIndigo
                                        ) else Text("실시간 도착정보 확인 대기", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    TransportKind.KTX -> {
                                        val schedule = snapshot.ktxSchedules[journey.id]
                                        if (schedule == null || schedule.departureTime.isBlank()) Text(
                                            "설정에서 KTX 열차번호와 출발·도착 시각을 입력해야 자동 탑승을 확인할 수 있습니다",
                                            style = MaterialTheme.typography.bodySmall
                                        ) else {
                                            val platformText = schedule.platform.takeIf(String::isNotBlank)?.let { "${it}번홈" } ?: "승차홈 직접 입력 필요"
                                            Text("${schedule.trainIdentifier} · ${schedule.departureTime} 출발 · $platformText", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                            Text("출발 시각·경로 근접·시속 20km 이상 조건으로 자동 탑승합니다", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    TransportKind.SUBWAY -> {
                                        Text(
                                            if (journey.id == JourneyId.COMMUTE) "하행 시간표 자료 기준: 2024-10-07 (Dev-1 원본)"
                                            else "상행 시간표 자료 기준: 2024-10-07 (Dev-1 원본)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        Text(progress.plannedSubwayText.ifBlank { "지하철 전체 시간표 확인 중" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        if (journey.id == JourneyId.COMMUTE) Text("빠른 환승 위치: 6-4", style = MaterialTheme.typography.bodySmall, color = TransitIndigo)
                                        Text("탑승 버튼을 누르면 가장 가까운 전체 시간표 열차를 한 번 고정합니다", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (boardingMessage.isNotBlank()) Text(boardingMessage, style = MaterialTheme.typography.bodySmall)
                                        Button(onClick = onBoardSubway, enabled = !boarding, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                                            Text(if (boarding) "열차 선택 중…" else "지하철 탑승 처리")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (isCurrent && phase == LegPhase.ONBOARD) {
                        val currentStop = leg.stops.getOrNull(progress.stopIndex) ?: leg.stops.firstOrNull().orEmpty()
                        val nextStop = leg.stops.getOrNull((progress.stopIndex + 1).coerceAtMost(leg.stops.lastIndex)) ?: currentStop
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.9f))) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("현재 이동 위치", style = MaterialTheme.typography.labelSmall, color = TransitIndigo, fontWeight = FontWeight.Bold)
                                Text("$currentStop  →  $nextStop", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (leg.kind == TransportKind.SUBWAY) progress.selectedSubwayTrip?.let { trip ->
                            Text("고정 열차 ${KtxScheduleResolver.displayClock(trip.departureEpochMillis)} · 이후 재선택하지 않음", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (index == 0 && !active) {
                        if (anyActive) Text("다른 이동이 진행 중입니다. 현재 여정을 먼저 완료하거나 취소하세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        else Button(onClick = onStart, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("${leg.displayName} 탑승예정 시작") }
                    }
                    if (isCurrent && phase == LegPhase.PLANNED) OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("탑승예정 취소") }
                }
            }
        }
    }
}

@Composable
private fun JourneyOverviewCard(journey: JourneyDefinition, active: Boolean, anyActive: Boolean) {
    val first = journey.legs.firstOrNull()
    val last = journey.legs.lastOrNull()
    val status = when {
        active -> "이동 중 · 자동 진행"
        anyActive -> "다른 경로가 진행 중입니다"
        else -> "첫 구간의 탑승예정만 직접 선택"
    }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(journey.id.label, style = MaterialTheme.typography.labelLarge, color = TransitIndigo, fontWeight = FontWeight.Bold)
                Text(
                    "${first?.origin.orEmpty()}  →  ${last?.destination.orEmpty()}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(status, style = MaterialTheme.typography.bodySmall, color = if (active) Color(0xFF874B00) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(color = Color(0xFFE8EDFF), shape = RoundedCornerShape(12.dp)) {
                Text("${journey.legs.size} 구간", Modifier.padding(horizontal = 10.dp, vertical = 8.dp), color = TransitIndigo, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ApiKeyNotice(onOpenSettings: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF2DF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(color = Color(0xFFFFE2B8), shape = RoundedCornerShape(50)) {
                Text("!", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Color(0xFF874B00), fontWeight = FontWeight.ExtraBold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("버스 실시간 도착정보 · API 키 미설정", style = MaterialTheme.typography.labelLarge, color = Color(0xFF744200), fontWeight = FontWeight.Bold)
                Text("설정에서 공공데이터포털 서비스 키를 등록해 주세요.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF744200))
            }
            TextButton(onClick = onOpenSettings) { Text("설정", color = Color(0xFF744200), fontWeight = FontWeight.Bold) }
        }
    }
}

private data class KtxForm(
    val departure: String = "", val arrival: String = "", val train: String = "",
    val platform: String = "", val car: String = "", val seat: String = ""
) {
    val hasAny: Boolean get() = listOf(departure, arrival, train, platform, car, seat).any(String::isNotBlank)
    fun toSchedule() = KtxSchedule(departure, arrival, train, platform, car, seat)
    companion object { fun from(value: KtxSchedule) = KtxForm(value.departureTime, value.arrivalTime, value.trainIdentifier, value.platform, value.car, value.seat) }
}

@Composable
private fun KtxSettingsDialog(
    initialCommute: KtxSchedule,
    initialReturn: KtxSchedule,
    initialApiKeyConfigured: Boolean,
    onDismiss: () -> Unit,
    onSave: (KtxSchedule?, KtxSchedule?, String?) -> Boolean
) {
    var commute by remember { mutableStateOf(KtxForm.from(initialCommute)) }
    var returning by remember { mutableStateOf(KtxForm.from(initialReturn)) }
    var error by remember { mutableStateOf("") }
    var apiKeyInput by remember { mutableStateOf("") }
    var clearApiKeyRequested by remember { mutableStateOf(false) }
    var isApiKeyConfigured by remember(initialApiKeyConfigured) { mutableStateOf(initialApiKeyConfigured) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).heightIn(max = 740.dp), shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("교통 정보 설정", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("버스 실시간 도착정보", style = MaterialTheme.typography.titleSmall, color = TransitIndigo, fontWeight = FontWeight.Bold)
                Text("API 키 · ${if (isApiKeyConfigured) "설정됨" else "미설정"}", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it; if (it.isNotBlank()) clearApiKeyRequested = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("공공데이터포털 서비스 키") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                Text("키는 Android Keystore로 암호화해 기기 안에만 저장합니다. 입력하지 않으면 기존 키를 유지합니다.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { apiKeyInput = ""; clearApiKeyRequested = true }) {
                    Text(if (clearApiKeyRequested) "키 삭제 예정" else "저장된 버스 API 키 삭제")
                }
                Text("KTX 수동 일정", style = MaterialTheme.typography.titleSmall, color = TransitIndigo, fontWeight = FontWeight.Bold)
                KtxEditor("출근 KTX · 대전 → 동대구", commute) { commute = it }
                KtxEditor("퇴근 KTX · 동대구 → 대전", returning) { returning = it }
                Text("현재 KTX 일정은 수동 입력 방식입니다. 승차홈·호차·좌석은 자동 조회하지 않으니 직접 확인해 입력해 주세요. 출발·도착은 HH:mm로 입력하세요.", style = MaterialTheme.typography.bodySmall)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("닫기") }
                    Button(onClick = {
                        val pattern = Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$")
                        val invalid = listOf(commute, returning).any { it.hasAny && (!pattern.matches(it.departure) || !pattern.matches(it.arrival) || it.train.isBlank()) }
                        if (invalid) error = "일정을 입력했다면 출발·도착 시각(HH:mm)과 열차번호를 모두 입력해 주세요."
                        else {
                            val apiKeyChange = when {
                                clearApiKeyRequested -> ""
                                apiKeyInput.isNotBlank() -> apiKeyInput.trim()
                                else -> null
                            }
                            val saved = onSave(
                                commute.takeIf(KtxForm::hasAny)?.toSchedule(),
                                returning.takeIf(KtxForm::hasAny)?.toSchedule(),
                                apiKeyChange
                            )
                            if (saved) {
                                if (apiKeyChange != null) isApiKeyConfigured = apiKeyChange.isNotBlank()
                            } else error = "설정 저장에 실패했습니다. 키를 다시 입력하거나 삭제한 뒤 재시도해 주세요."
                        }
                    }) { Text("저장") }
                }
            }
        }
    }
}

@Composable
private fun KtxEditor(title: String, form: KtxForm, onChange: (KtxForm) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(form.train, { onChange(form.copy(train = it)) }, Modifier.fillMaxWidth(), label = { Text("열차번호 (예: KTX-산천 054)") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(form.departure, { onChange(form.copy(departure = it)) }, Modifier.weight(1f), label = { Text("출발 HH:mm") }, singleLine = true)
            OutlinedTextField(form.arrival, { onChange(form.copy(arrival = it)) }, Modifier.weight(1f), label = { Text("도착 HH:mm") }, singleLine = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(form.platform, { onChange(form.copy(platform = it)) }, Modifier.weight(1f), label = { Text("승차홈") }, singleLine = true)
            OutlinedTextField(form.car, { onChange(form.copy(car = it)) }, Modifier.weight(1f), label = { Text("호차") }, singleLine = true)
            OutlinedTextField(form.seat, { onChange(form.copy(seat = it)) }, Modifier.weight(1f), label = { Text("좌석") }, singleLine = true)
        }
    }
}
