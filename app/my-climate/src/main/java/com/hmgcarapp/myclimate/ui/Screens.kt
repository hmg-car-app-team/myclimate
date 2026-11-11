package com.hmgcarapp.myclimate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import com.hmgcarapp.core.drive.DriveState
import com.hmgcarapp.core.ui.rememberDriveSnapshot
import com.hmgcarapp.myclimate.app.AppController
import com.hmgcarapp.myclimate.app.UiState
import com.hmgcarapp.myclimate.data.Regions
import com.hmgcarapp.myclimate.domain.*
import com.hmgcarapp.pleos.designsystem.component.PleosButton
import com.hmgcarapp.pleos.designsystem.component.PleosButtonHierarchy
import com.hmgcarapp.pleos.designsystem.component.PleosListItem
import com.hmgcarapp.pleos.designsystem.component.PleosPanel
import com.hmgcarapp.pleos.designsystem.component.PleosPopup
import com.hmgcarapp.pleos.designsystem.component.PleosPopupSize
import com.hmgcarapp.pleos.designsystem.component.PleosScaffold
import com.hmgcarapp.pleos.designsystem.component.PleosSegmentedTabs
import com.hmgcarapp.pleos.designsystem.component.PleosStatus
import com.hmgcarapp.pleos.designsystem.component.PleosStatusBadge
import com.hmgcarapp.pleos.designsystem.component.PleosStepper
import com.hmgcarapp.pleos.designsystem.component.PleosSwitch
import com.hmgcarapp.pleos.designsystem.component.rememberPleosToastState
import com.hmgcarapp.pleos.designsystem.theme.PleosSpacing
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme
import java.util.Locale

private enum class Page(val label: String) { NOW("지금"), PROFILES("프로필"), RULES("규칙"), SETTINGS("설정") }

@Composable
private fun Txt(t: String, style: TextStyle = PleosTheme.typography.bodyMedium, secondary: Boolean = false, modifier: Modifier = Modifier) =
    Text(t, style = style, color = if (secondary) PleosTheme.colors.textDarkSecondary else PleosTheme.colors.textDarkPrimary, modifier = modifier)

@Composable
private fun KV(k: String, v: String, tag: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Txt(k, secondary = true); Txt(v, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
    }
}

@Composable
private fun Scroll(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(PleosSpacing.m)) { content() }
}

internal fun fmtTemp(c: Float?) = c?.let { String.format(Locale.KOREA, "%.1f°C", it) } ?: "알 수 없음"

@Composable
fun MyClimateApp(c: AppController) {
    val s by c.state.collectAsState()
    val snap = rememberDriveSnapshot(c.signals)
    LaunchedEffect(snap.state) { c.onDriveState(snap.state) }
    // 화면은 core 스냅샷(초기값 DRIVING)을 직접 쓴다: 컨트롤러 반영 지연으로 정차 UI 가 첫 프레임에 열리지 않게
    val driving = snap.state == DriveState.DRIVING
    val toast = rememberPleosToastState()
    LaunchedEffect(s.toast, driving) { if (!driving) s.toast?.let { toast.show(it); c.clearToast() } }
    LaunchedEffect(driving) { if (driving) toast.dismiss() }
    var page by remember { mutableStateOf(Page.NOW) }
    val paneWidth = PleosTheme.window.appLandscapeWidth * 0.4f

    if (!driving) s.fs.pending?.let { sg ->
        PleosPopup(
            title = "자동으로 켤까요?", description = sg.text,
            confirmLabel = "자동으로 켜기", onConfirm = { c.acceptSuggestion() },
            onDismissRequest = { c.dismissSuggestion() }, cancelLabel = "지금은 안 함",
        )
    }
    if (!driving && s.fs.pending == null) s.seatOffer?.let { o ->
        PleosPopup(
            title = "프로필로 맞출까요?", description = o.text,
            confirmLabel = "맞추기", onConfirm = { c.acceptSeatOffer() },
            onDismissRequest = { c.dismissSeatOffer() }, cancelLabel = "지금은 안 함",
        )
    }
    if (!driving && s.netLoaded && !s.netDecided && !s.netPromptSnoozed) NetNoticePopup(c)
    PleosScaffold(
        title = "마이클라이밋",
        subtitle = "${s.envBadge} · ${if (driving) "주행 중" else "정차 중"}" + if (c.debuggable) " · ${snap.reason}" else "",
        toastState = toast,
        actions = {
            if (c.debuggable) PleosSegmentedTabs(
                items = listOf("실신호", "주행"), selectedIndex = if (c.demoDriving) 1 else 0,
                onSelect = { c.setDemoDrive(if (it == 1) "DRIVING" else null) }, modifier = Modifier.widthIn(max = paneWidth / 2).testTag("demo_drive"),
            )
        },
        supportingPane = if (driving || s.loading) null else { m -> Column(m) { Scroll { StatusPanels(s) } } },
        supportingPaneWidth = paneWidth,
    ) { mode ->
        when {
            driving -> DrivingView(s)
            s.loading -> PleosPanel(Modifier.fillMaxWidth()) { Txt("불러오는 중…", secondary = true, modifier = Modifier.testTag("loading")) }
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(PleosSpacing.m)) {
                PleosSegmentedTabs(Page.entries.map { it.label }, page.ordinal, { page = Page.entries[it] }, Modifier.fillMaxWidth().testTag("page_tabs"))
                Scroll {
                    // 세로(1단)에서는 상태 패널을 '지금' 탭 맨 위에 둔다(가로는 좌측 pane)
                    when (page) {
                        Page.NOW -> { if (mode != com.hmgcarapp.pleos.designsystem.theme.PleosWindowMode.Landscape) StatusPanels(s); NowPage(c, s) }
                        Page.PROFILES -> ProfilesPage(c, s)
                        Page.RULES -> RulesPage(c, s)
                        Page.SETTINGS -> SettingsPage(c, s)
                    }
                }
            }
        }
    }
}

// ---------------- 주행 중: 한 줄 안내만 ----------------
@Composable
private fun DrivingView(s: UiState) {
    val fs = s.fs
    Column(Modifier.fillMaxSize().testTag("driving_view")) {
        PleosPanel(Modifier.fillMaxWidth()) {
            Txt(fs.note ?: "주행 중이에요. 공조 설정은 정차 후에 바꿀 수 있어요", PleosTheme.typography.bodyLarge, modifier = Modifier.testTag("driving_line"))
            val r = fs.hw.current.recirc
            Txt("스마트 내외기: " + RecircView.label(s.fs.p.smartRecirc, fs.hw.connected, fs.hw.caps.recirc, r), secondary = true, modifier = Modifier.testTag("driving_recirc"))
        }
    }
}

// ---------------- 상태(좌 pane / 세로 상단) ----------------
@Composable
private fun StatusPanels(s: UiState) {
    val fs = s.fs
    PleosPanel(Modifier.fillMaxWidth(), title = "지금 상황") {
        KV("외기온도", fmtTemp(s.ctx.outsideC), "k_outside")
        KV("시간대", "${TimeSlot.of(s.ctx.hour).label} (${s.ctx.hour}시)")
        KV("날씨", s.weather?.label ?: "알 수 없음")
        KV("대기질", s.air?.label ?: "알 수 없음")
        Box(Modifier.testTag("data_label")) { PleosStatusBadge(s.envBadge, if (s.envBadge.startsWith("실시간")) PleosStatus.Positive else PleosStatus.Neutral) }
        if (s.simActive) Txt("시연 값 사용 중", PleosTheme.typography.labelMedium, secondary = true)
        s.airNote?.let { Txt(it, PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("air_note")) }
        if (s.demoWeather != null || s.demoAir != null) Box(Modifier.testTag("demo_badge")) { PleosStatusBadge("시연 값 (실데이터 아님)", PleosStatus.Neutral) }
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "차량 공조 (운전석)") {
        val hw = fs.hw
        PleosStatusBadge(if (hw.connected) "연결됨" else if (hw.error != null) "연결 실패" else "연결 중", if (hw.connected) PleosStatus.Positive else if (hw.error != null) PleosStatus.Negative else PleosStatus.Neutral)
        if (hw.connected && !hw.caps.any) Txt("제어 가능한 공조 항목이 없어요.", PleosTheme.typography.labelMedium, secondary = true)
        val cur = hw.current
        KV("설정 온도", fmtTemp(cur.tempC)); KV("풍량", cur.fan?.toString() ?: "-")
        KV("에어컨", cur.ac?.let { if (it) "켜짐" else "꺼짐" } ?: "-"); KV("내기 순환", cur.recirc?.let { if (it) "켜짐" else "꺼짐" } ?: "-")
        KV("시트 열선", cur.seatHeat?.let { "${it}단계" } ?: "-"); KV("시트 통풍", cur.seatVent?.let { "${it}단계" } ?: "-"); KV("핸들 열선", cur.wheelHeat?.let { "${it}단계" } ?: "-")
        if (hw.error != null) Txt(hw.error, PleosTheme.typography.labelMedium, secondary = true)
    }
}

// ---------------- 지금 ----------------
@Composable
private fun NowPage(c: AppController, s: UiState) {
    val fs = s.fs
    val base = fs.p.selected?.settings ?: ClimateSettings()
    val res = PresetEngine.resolve(base, fs.p.rules, s.ctx)
    PleosPanel(Modifier.fillMaxWidth(), title = "적용될 설정") {
        Txt("프로필: ${fs.p.selected?.name ?: "선택 없음"}", secondary = true)
        Txt(if (res.applied.isEmpty()) "지금 상황에 맞는 규칙이 없어요" else "맞는 규칙: " + res.applied.joinToString(", ") { it.name }, secondary = true, modifier = Modifier.testTag("matched_rules"))
        // 차량이 지원하지 않는 항목은 적용되지 않으므로 표시에서도 뺀다(연결 전에는 원본 그대로)
        val fk = FeelKeep.adjust(res.settings.tempC, s.ctx.outsideC, fs.p.feelKeep, fs.hw.caps.limits)
        val shown = if (fk.applied) res.settings.copy(tempC = fk.tempC) else res.settings
        Txt(describe(if (fs.hw.connected && fs.hw.caps.any) fs.hw.caps.filter(shown) else shown), modifier = Modifier.testTag("resolved"))
        if (fk.applied) Txt("체감 유지 적용: 목표 온도 ${FeelKeep.label(fk.delta)} (외기온 ${fmtTemp(s.ctx.outsideC)} 기반)", PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("feel_applied"))
        PleosButton("지금 적용", onClick = { c.applyNow() }, enabled = fs.hw.connected && fs.hw.caps.any, modifier = Modifier.testTag("apply_now"))
        fs.note?.let { Txt("최근: $it", PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("note")) }
        if (!fs.hw.connected) Txt("차량 공조에 연결되면 적용할 수 있어요.", PleosTheme.typography.labelMedium, secondary = true)
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "스마트 내외기") {
        val rs = fs.recirc
        Txt(if (fs.p.smartRecirc) "켜짐: 대기질이 나쁘면 내기, 오래되면 외기로 자동 전환해요" else "꺼짐 (설정 탭에서 켤 수 있어요)", secondary = true)
        Txt("현재: " + RecircView.label(fs.p.smartRecirc, fs.hw.connected, fs.hw.caps.recirc, fs.hw.current.recirc), PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("recirc_state"))
    }
}

internal fun describe(s: ClimateSettings): String {
    val parts = buildList {
        s.tempC?.let { add("온도 ${fmtTemp(it)}") }; s.fan?.let { add("풍량 $it") }; s.fanDir?.let { add("송풍 ${it.label}") }
        s.ac?.let { add("에어컨 ${if (it) "켬" else "끔"}") }; s.recirc?.let { add(if (it) "내기" else "외기") }
        s.seatHeat?.let { add("시트열선 ${it}") }; s.seatVent?.let { add("통풍 ${it}") }; s.wheelHeat?.let { add("핸들열선 ${it}") }
    }
    return if (parts.isEmpty()) "변경할 항목 없음" else parts.joinToString(" · ")
}

// ---------------- 프로필 ----------------
@Composable
private fun ProfilesPage(c: AppController, s: UiState) {
    val p = s.fs.p
    PleosPanel(Modifier.fillMaxWidth(), title = "프로필 (최대 ${Edits.MAX_PROFILES}개)") {
        if (p.profiles.isEmpty()) Txt("프로필이 없어요. ‘현재 값으로 새 프로필’을 눌러 만드세요. 운전자는 자동으로 인식되지 않아 정차 중에 직접 고릅니다.", secondary = true)
        p.profiles.forEach { pr ->
            PleosListItem(title = pr.name, description = describe(pr.settings), selected = pr.id == p.selectedId, onClick = { c.selectProfile(pr.id) },
                trailing = { if (pr.id == p.selectedId) PleosStatusBadge("선택됨", PleosStatus.Positive) })
        }
        PleosButton("현재 값으로 새 프로필", onClick = { c.addProfile() }, hierarchy = PleosButtonHierarchy.Secondary, enabled = p.profiles.size < Edits.MAX_PROFILES && !s.fs.hw.current.isEmpty, modifier = Modifier.testTag("add_profile"))
        if (s.fs.hw.current.isEmpty) Txt("차량 공조 값을 읽으면 새 프로필을 만들 수 있어요.", PleosTheme.typography.labelMedium, secondary = true)
    }
    p.selected?.let { sel ->
        PleosPanel(Modifier.fillMaxWidth(), title = "${sel.name} 편집") {
            SettingsEditor(sel.settings, s.fs.hw.caps.limits) { f -> c.editProfile(sel.id, f) }
            var ask by remember(sel.id) { mutableStateOf(false) }
            PleosButton("이 프로필 삭제", onClick = { ask = true }, hierarchy = PleosButtonHierarchy.Secondary, modifier = Modifier.testTag("delete_profile"))
            if (ask) PleosPopup(title = "‘${sel.name}’을(를) 삭제할까요?", description = "되돌릴 수 없어요.", confirmLabel = "삭제",
                onConfirm = { ask = false; c.deleteProfile(sel.id) }, onDismissRequest = { ask = false }, cancelLabel = "취소")
        }
    }
}

/** 항목별 ‘적용’ 스위치(꺼짐 = 건드리지 않음) + 값 조절. */
@Composable
internal fun SettingsEditor(v: ClimateSettings, lim: Limits, onEdit: ((ClimateSettings) -> ClimateSettings) -> Unit) {
    @Composable fun <T> Opt(label: String, cur: T?, default: T, set: (ClimateSettings, T?) -> ClimateSettings, value: @Composable (T) -> Unit) {
        // 스위치(라벨이 weight 로 남은 폭을 모두 차지)와 값 조절기를 같은 Row 에 두면 조절기가 폭 0 으로 밀려 사라진다 -> 세로로 쌓는다.
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PleosSpacing.xs)) {
            PleosSwitch(checked = cur != null, onCheckedChange = { on -> onEdit { set(it, if (on) default else null) } }, label = label, modifier = Modifier.fillMaxWidth())
            if (cur != null) value(cur)
        }
    }
    Opt("온도", v.tempC, 22f, { s, x -> s.copy(tempC = x) }) { t ->
        PleosStepper(fmtTemp(t), { onEdit { it.copy(tempC = ((it.tempC ?: t) - 0.5f).coerceAtLeast(lim.tempMin)) } }, { onEdit { it.copy(tempC = ((it.tempC ?: t) + 0.5f).coerceAtMost(lim.tempMax)) } })
    }
    Opt("풍량", v.fan, 3, { s, x -> s.copy(fan = x) }) { f ->
        PleosStepper("$f", { onEdit { it.copy(fan = ((it.fan ?: f) - 1).coerceAtLeast(lim.fanMin)) } }, { onEdit { it.copy(fan = ((it.fan ?: f) + 1).coerceAtMost(lim.fanMax)) } })
    }
    Opt("송풍 방향", v.fanDir, FanDir.FACE, { s, x -> s.copy(fanDir = x) }) { d ->
        PleosSegmentedTabs(FanDir.entries.map { it.label }, d.ordinal, { i -> onEdit { it.copy(fanDir = FanDir.entries[i]) } }, Modifier.fillMaxWidth())
    }
    Opt("에어컨", v.ac, true, { s, x -> s.copy(ac = x) }) { a -> PleosSwitch(a, { x -> onEdit { it.copy(ac = x) } }, Modifier.fillMaxWidth(), label = if (a) "켬" else "끔") }
    Opt("내기 순환", v.recirc, false, { s, x -> s.copy(recirc = x) }) { a -> PleosSwitch(a, { x -> onEdit { it.copy(recirc = x) } }, Modifier.fillMaxWidth(), label = if (a) "내기" else "외기") }
    Opt("시트 열선", v.seatHeat, 0, { s, x -> s.copy(seatHeat = x) }) { n ->
        PleosStepper(if (n == 0) "끔" else "${n}단계", { onEdit { it.copy(seatHeat = ((it.seatHeat ?: n) - 1).coerceAtLeast(0)) } }, { onEdit { it.copy(seatHeat = ((it.seatHeat ?: n) + 1).coerceAtMost(lim.seatHeatMax)) } })
    }
    Opt("시트 통풍", v.seatVent, 0, { s, x -> s.copy(seatVent = x) }) { n ->
        PleosStepper(if (n == 0) "끔" else "${n}단계", { onEdit { it.copy(seatVent = ((it.seatVent ?: n) - 1).coerceAtLeast(0)) } }, { onEdit { it.copy(seatVent = ((it.seatVent ?: n) + 1).coerceAtMost(lim.seatVentMax)) } })
    }
    Opt("핸들 열선", v.wheelHeat, 0, { s, x -> s.copy(wheelHeat = x) }) { n ->
        PleosStepper(if (n == 0) "끔" else "${n}단계", { onEdit { it.copy(wheelHeat = ((it.wheelHeat ?: n) - 1).coerceAtLeast(0)) } }, { onEdit { it.copy(wheelHeat = ((it.wheelHeat ?: n) + 1).coerceAtMost(lim.wheelHeatMax)) } })
    }
}

// ---------------- 규칙 ----------------
private fun ruleCond(r: PresetRule): String = buildList {
    if (r.minC != null || r.maxC != null) add("외기 ${r.minC?.let { "%.0f".format(it) } ?: ""}~${r.maxC?.let { "%.0f".format(it) } ?: ""}°C")
    if (r.fromHour != null && r.toHour != null) add("${r.fromHour}~${r.toHour}시")
    r.weather?.let { add(it.label) }
}.ifEmpty { listOf("항상") }.joinToString(" · ")

@Composable
private fun RulesPage(c: AppController, s: UiState) {
    val rules = s.fs.p.rules
    var askRule by remember { mutableStateOf<PresetRule?>(null) }
    askRule?.let { r ->
        PleosPopup(title = "‘${r.name}’ 규칙을 삭제할까요?", description = "되돌릴 수 없어요.", confirmLabel = "삭제",
            onConfirm = { askRule = null; c.deleteRule(r.id) }, onDismissRequest = { askRule = null }, cancelLabel = "취소")
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "상황별 규칙 (최대 ${Edits.MAX_RULES}개)") {
        Txt("프로필 위에 맞는 규칙이 덧씌워져요. 더 구체적인 규칙이 우선해요.", PleosTheme.typography.labelMedium, secondary = true)
        rules.forEach { r ->
            PleosListItem(
                title = r.name + " [${r.source.label}]", description = ruleCond(r) + " → " + describe(r.patch),
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(PleosSpacing.s), verticalAlignment = Alignment.CenterVertically) {
                        PleosSwitch(r.enabled, { c.toggleRule(r.id) })
                        PleosButton("삭제", onClick = { askRule = r }, hierarchy = PleosButtonHierarchy.Secondary)
                    }
                },
            )
        }
        if (rules.isEmpty()) Txt("규칙이 없어요.", secondary = true)
    }
    RuleEditor(c, s)
}

@Composable
private fun RuleEditor(c: AppController, s: UiState) {
    var band by remember { mutableStateOf(-1) }
    var slot by remember { mutableStateOf(-1) }
    var wx by remember { mutableStateOf(-1) }
    var patch by remember { mutableStateOf(ClimateSettings(seatHeat = 2)) }
    PleosPanel(Modifier.fillMaxWidth(), title = "규칙 추가") {
        Txt("외기온도", secondary = true)
        PleosSegmentedTabs(listOf("전체") + TempBand.entries.map { it.label }, band + 1, { band = it - 1 }, Modifier.fillMaxWidth())
        Txt("시간대", secondary = true)
        PleosSegmentedTabs(listOf("전체") + TimeSlot.entries.map { it.label }, slot + 1, { slot = it - 1 }, Modifier.fillMaxWidth())
        Txt("날씨", secondary = true)
        PleosSegmentedTabs(listOf("전체") + Weather.entries.map { it.label }, wx + 1, { wx = it - 1 }, Modifier.fillMaxWidth())
        Txt("적용할 설정", secondary = true)
        SettingsEditor(patch, s.fs.hw.caps.limits) { f -> patch = f(patch) }
        PleosButton("규칙 저장", onClick = {
            val b = TempBand.entries.getOrNull(band); val t = TimeSlot.entries.getOrNull(slot)
            val name = listOfNotNull(b?.label, t?.label, Weather.entries.getOrNull(wx)?.label).ifEmpty { listOf("항상") }.joinToString(" ")
            c.upsertRule(PresetRule(c.newRuleId(), name, minC = b?.minC, maxC = b?.maxC, fromHour = t?.from, toHour = t?.to, weather = Weather.entries.getOrNull(wx), patch = patch))
        }, enabled = !patch.isEmpty, modifier = Modifier.testTag("save_rule"))
    }
}

// ---------------- 설정 ----------------
@Composable
private fun SettingsPage(c: AppController, s: UiState) {
    val p = s.fs.p
    var ask by remember { mutableStateOf<String?>(null) }
    PleosPanel(Modifier.fillMaxWidth(), title = "자동 동작") {
        PleosSwitch(p.autoApply, { c.setAutoApply(it) }, label = "정차하면 프로필·규칙 자동 적용", modifier = Modifier.testTag("set_auto"))
        PleosSwitch(p.smartRecirc, { c.setSmartRecirc(it) }, label = "스마트 내외기 전환", modifier = Modifier.testTag("set_smart"))
        Txt("스마트 내외기: 대기질이 나쁘면 내기, 내기가 ${RecircPolicy.MAX_MS / 60_000}분(대기질 나쁨 ${RecircPolicy.MAX_BAD_AIR_MS / 60_000}분) 넘으면 외기로 자동 전환하고 한 줄로 알려요. 직접 바꾸면 ${RecircPolicy.HOLD_MS / 60_000}분간 자동 전환을 멈춰요.", PleosTheme.typography.labelMedium, secondary = true)
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "체감 온도 유지") {
        PleosSwitch(p.feelKeep, { c.setFeelKeep(it) }, label = "외기온 기반 체감 보정", modifier = Modifier.testTag("set_feel"))
        Txt("외기온만 봐요(일사량·체감 센서는 사용하지 않아요). 외기온이 ${FeelKeep.HOT_NOTE}일 때 적용되는 목표 온도를 ±1~2℃ 보정하고, 차량 허용 범위를 넘지 않아요. 외기온을 알 수 없으면 보정하지 않아요.", PleosTheme.typography.labelMedium, secondary = true)
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "시연용 날씨·대기질") {
        Txt("실제 연동 값이 아닌 시연용 값을 고르면 규칙·스마트 내외기에 그 값이 쓰여요. 이번 실행 동안만 유지되고 저장하지 않아요.", PleosTheme.typography.labelMedium, secondary = true)
        Txt("날씨", secondary = true)
        PleosSegmentedTabs(DemoEnv.WEATHER_LABELS, DemoEnv.WEATHER.indexOf(s.demoWeather).coerceAtLeast(0), { c.setDemoWeather(DemoEnv.weatherAt(it)) }, Modifier.fillMaxWidth().testTag("demo_weather"))
        Txt("대기질", secondary = true)
        PleosSegmentedTabs(DemoEnv.AIR_LABELS, DemoEnv.AIR.indexOf(s.demoAir).coerceAtLeast(0), { c.setDemoAir(DemoEnv.airAt(it)) }, Modifier.fillMaxWidth().testTag("demo_air"))
        if (s.demoWeather != null || s.demoAir != null) PleosStatusBadge("시연 값 사용 중", PleosStatus.Neutral)
    }
    NetSettingsPanel(c, s)
    PleosPanel(Modifier.fillMaxWidth(), title = "저장된 데이터") {
        Txt("프로필·규칙·습관 통계(상황별 ‘켠 날짜’)만 이 기기에 평문으로 저장해요. 습관 통계에는 관측 날짜 번호(최대 14일)가, 거절한 제안에는 다시 제안하지 않을 시각(에포크 ms)이 저장돼요. 위치·음성은 저장하지 않아요.", PleosTheme.typography.labelMedium, secondary = true)
        KV("프로필", "${p.profiles.size}개"); KV("규칙", "${p.rules.size}개"); KV("학습된 습관", "${p.habits.size}건")
        if (s.corruptKept) Txt("저장 파일이 손상돼 처음 상태로 시작했어요. ‘모든 데이터 삭제’로 손상본을 지울 수 있어요.", PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("corrupt_kept"))
        if (s.saveHeld) Txt("저장하지 못해 이번 실행 동안 저장을 멈췄어요. 앱을 다시 시작하거나 ‘모든 데이터 삭제’로 회복해요.", PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("save_held"))
        Row(horizontalArrangement = Arrangement.spacedBy(PleosSpacing.m)) {
            PleosButton("학습 기록 지우기", onClick = { ask = "learned" }, hierarchy = PleosButtonHierarchy.Secondary, enabled = p.habits.isNotEmpty() || p.dismissed.isNotEmpty())
            PleosButton("모든 데이터 삭제", onClick = { ask = "all" }, hierarchy = PleosButtonHierarchy.Secondary, modifier = Modifier.testTag("clear_all"))
        }
    }
    if (c.debuggable) Txt("시연 모드(debuggable): adb 로 외기온도·시간·날씨·대기질·조작을 주입할 수 있어요. 주입된 값은 화면에 ‘시연 값 사용 중’으로 표시돼요.", PleosTheme.typography.labelMedium, secondary = true)
    ask?.let { which ->
        PleosPopup(
            title = if (which == "all") UiTexts.CLEAR_ALL_TITLE else "학습 기록을 지울까요?",
            description = if (which == "all") UiTexts.CLEAR_ALL_DESC else "습관 통계와 거절 목록이 지워져요. 프로필과 규칙은 유지돼요.",
            confirmLabel = "삭제", onConfirm = { ask = null; if (which == "all") c.clearAllData() else c.clearLearned() },
            onDismissRequest = { ask = null }, cancelLabel = "취소",
        )
    }
}

// ---------------- 날씨·대기질 네트워크 사용 ----------------
private const val NET_NOTICE = "선택한 지역의 대표 좌표(예: 서울시청 부근)를 Open-Meteo 서버(open-meteo.com)로 보내 날씨와 대기질을 받아요. 계정·기기·차량 식별자와 기기 위치는 보내지 않아요(서버는 접속 IP 를 볼 수 있어요). 동의하지 않으면 인터넷을 쓰지 않고 기본 샘플을 보여줘요. 설정에서 언제든 철회할 수 있어요. Open-Meteo 는 서버 로그를 최대 90일 보관해요(open-meteo.com/en/terms)."
private const val NET_ATTRIBUTION = "날씨: Open-Meteo.com (CC BY 4.0). 대기질: Copernicus Atmosphere Monitoring Service(CAMS) 모델 데이터를 Open-Meteo 로 받아요. 대기질은 약 45km 격자 모델 추정이라 참고용이에요. 무료 비상업 이용 범위의 시연용 연동이에요."

@Composable
private fun NetNoticePopup(c: AppController) {
    PleosPopup(
        title = "인터넷으로 날씨·대기질을 받을까요?", description = null,
        confirmLabel = "동의하고 사용", onConfirm = { c.setNetConsent(true) },
        onDismissRequest = { c.snoozeNetPrompt() }, cancelLabel = "사용 안 함", onCancel = { c.setNetConsent(false) },
        size = PleosPopupSize.Large,
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = PleosTheme.dimensions.popupMaxHeight / 2).verticalScroll(rememberScrollState()).testTag("net_notice")) {
            Txt(NET_NOTICE); Txt(NET_ATTRIBUTION, PleosTheme.typography.labelMedium, secondary = true)
        }
    }
}

@Composable
private fun NetSettingsPanel(c: AppController, s: UiState) {
    PleosPanel(Modifier.fillMaxWidth(), title = "날씨·대기질 (Open-Meteo)") {
        PleosSwitch(s.netConsent, { if (s.netLoaded) c.setNetConsent(it) }, label = "인터넷으로 날씨·대기질 받기", modifier = Modifier.testTag("set_net"))
        Txt(NET_NOTICE, PleosTheme.typography.labelMedium, secondary = true)
        Txt("지역 (기기 위치는 쓰지 않아요)", secondary = true)
        Column(Modifier.fillMaxWidth().heightIn(max = PleosTheme.dimensions.popupMaxHeight / 2).verticalScroll(rememberScrollState()).testTag("region_list")) {
            Regions.ALL.forEach { r ->
                PleosListItem(title = r.label, selected = r.id == s.regionId, onClick = { if (s.netLoaded) c.selectRegion(r.id) },
                    trailing = { if (r.id == s.regionId) PleosStatusBadge("선택됨", PleosStatus.Positive) })
            }
        }
        Txt("현재 출처: ${s.envBadge}", PleosTheme.typography.labelMedium, secondary = true)
        Txt(NET_ATTRIBUTION, PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("attribution"))
    }
}
