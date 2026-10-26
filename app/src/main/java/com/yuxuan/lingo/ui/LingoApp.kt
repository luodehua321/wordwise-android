package com.yuxuan.lingo.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.delay
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.yuxuan.lingo.R
import com.yuxuan.lingo.domain.ProfileNames
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// v2.0 palette: deep ink, clear blue, warm amber, soft paper. The tinted
// containers stay low-saturation so long study sessions remain calm.
private val Leaf = Color(0xFF3B6FE0)
private val LeafDeep = Color(0xFF16324F)
private val Mint = Color(0xFFE8F0FF)
private val Cream = Color(0xFFF4F6FA)
private val Ink = Color(0xFF16283C)
private val Muted = Color(0xFF5D6C80)
private val Line = Color(0xFFE1E7EF)
private val Coral = Color(0xFFD25560)
private val Sun = Color(0xFFC98A3C)
private val Berry = Color(0xFF7B61C9)
private val Sky = Color(0xFF2F8F9D)
private val Paper = Color(0xFFFFFFFF)
private val Amber = Color(0xFFE8A34A)
private val Forest = LeafDeep
private val Sage = Mint
private val Clay = Coral
private val NavigationClearance = 72.dp
private val CardShape = RoundedCornerShape(24.dp)
private val HeroShape = RoundedCornerShape(30.dp)
private val BubbleShape = RoundedCornerShape(18.dp)
private val LocalPronunciationAccent = staticCompositionLocalOf { PronunciationAccent.US }
private val AppPages = listOf(AppTab.LEARN, AppTab.MISTAKES, AppTab.PROFILE)
private val LocalFeedbackTap = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun LingoApp(state: AppUiState, onAction: (AppAction) -> Unit) {
    val currentAction by rememberUpdatedState(onAction)
    val currentTab by rememberUpdatedState(state.selectedTab)
    val pager = rememberPagerState(initialPage = if (state.selectedTab == AppTab.RECORDS) 2 else AppPages.indexOf(state.selectedTab).coerceAtLeast(0), pageCount = { AppPages.size })
    LaunchedEffect(state.selectedTab) {
        val target = if (state.selectedTab == AppTab.RECORDS) 2 else AppPages.indexOf(state.selectedTab).coerceAtLeast(0)
        if (pager.settledPage != target) pager.animateScrollToPage(target)
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { index ->
            if (currentTab != AppPages[index] && !(currentTab == AppTab.RECORDS && index == 2))
                currentAction(AppAction.SelectTab(AppPages[index]))
        }
    }
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Ink, onPrimary = Paper,
            primaryContainer = Mint, onPrimaryContainer = LeafDeep,
            secondary = Leaf, onSecondary = Paper,
            secondaryContainer = Color(0xFFE8F0FF), onSecondaryContainer = Color(0xFF203E70),
            tertiary = Amber, onTertiary = Ink,
            tertiaryContainer = Color(0xFFFFF3E2), onTertiaryContainer = Color(0xFF6E4A18),
            background = Cream, onBackground = Ink, surface = Paper,
            onSurface = Ink, surfaceVariant = Mint, onSurfaceVariant = Muted,
            surfaceTint = Leaf, outline = Line, outlineVariant = Line,
            surfaceBright = Paper, surfaceDim = Color(0xFFDDE2E9),
            surfaceContainerLowest = Paper, surfaceContainerLow = Color(0xFFF8F9FC),
            surfaceContainer = Color(0xFFEEF1F6), surfaceContainerHigh = Color(0xFFE7EBF1),
            surfaceContainerHighest = Color(0xFFDCE2EB),
            error = Coral, onError = Paper,
            errorContainer = Color(0xFFFDECEE), onErrorContainer = Color(0xFF7A2C34),
            inverseSurface = Ink, inverseOnSurface = Paper,
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(10.dp),
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(26.dp),
            extraLarge = RoundedCornerShape(32.dp),
        ),
        typography = Typography(
            headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
            headlineMedium = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
            titleLarge = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
            titleMedium = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 26.sp),
            bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 23.sp),
            bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 19.sp),
            labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        ),
    ) {
        CompositionLocalProvider(LocalFeedbackTap provides { onAction(AppAction.FeedbackTap) },
            LocalPronunciationAccent provides state.feedback.pronunciationAccent) {
            Surface(color = Cream, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    AppBackdrop()
                    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                        val wide = maxWidth >= 840.dp
                        val navigationBackdrop = if (Build.VERSION.SDK_INT >= 31 && !wide) rememberGraphicsLayer() else null
                        var pageOrigin by remember { mutableStateOf(Offset.Zero) }
                        val railBackdrop = if (wide && Build.VERSION.SDK_INT >= 31) rememberGraphicsLayer() else null
                        var railOrigin by remember { mutableStateOf(Offset.Zero) }
                        if (wide) Box(Modifier.fillMaxSize()
                            .onGloballyPositioned { railOrigin = it.positionInRoot() }
                            .drawWithContent {
                                if (railBackdrop != null) {
                                    railBackdrop.record { this@drawWithContent.drawContent() }
                                    drawLayer(railBackdrop)
                                } else drawContent()
                            }) { AppBackdrop() }
                        Row(Modifier.fillMaxSize()) {
                            if (wide) AppNavigation(state.selectedTab, true, onAction, backdrop = railBackdrop, contentOrigin = railOrigin)
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                // The navigation samples only the page beneath it, never itself.
                                Box(Modifier.fillMaxSize().onGloballyPositioned { pageOrigin = it.positionInRoot() }
                                    .drawWithContent {
                                        if (navigationBackdrop != null) {
                                            navigationBackdrop.record { this@drawWithContent.drawContent() }
                                            drawLayer(navigationBackdrop)
                                        } else drawContent()
                                    }) {
                                    AppBackdrop()
                                    when {
                                        state.isLoading -> LoadingScreen()
                                        state.errorMessage != null -> MessageState(
                                            icon = { Icon(Icons.Outlined.CloudOff, null, Modifier.size(34.dp)) },
                                            title = "暂时没有准备好", body = state.errorMessage,
                                            actionLabel = "重新加载", onClick = { onAction(AppAction.Retry) },
                                            bottomClearance = if (wide) 0.dp else NavigationClearance,
                                        )
                                        else -> HorizontalPager(state = pager, beyondViewportPageCount = 0,
                                            key = { AppPages[it].name },
                                            modifier = Modifier.fillMaxSize().testTag("appPager")) { page ->
                                            val pageAction: (AppAction) -> Unit = { action ->
                                                if (pager.currentPage == page && !pager.isScrollInProgress) onAction(action)
                                            }
                                            when (AppPages[page]) {
                                                AppTab.LEARN -> LearnScreen(state, wide, pageAction)
                                                AppTab.MISTAKES -> MistakesScreen(state, wide, pageAction)
                                                else -> ProfileScreen(state.profile, state.stats, state.feedback, wide, state.isWorking, pageAction)
                                            }
                                        }
                                    }
                                }
                                if (!wide) AppNavigation(state.selectedTab, false, onAction,
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                    backdrop = navigationBackdrop, contentOrigin = pageOrigin,
                                    pagePosition = pager.currentPage + pager.currentPageOffsetFraction)
                            }
                        }
                        state.selectedWord?.let { word ->
                            WordDialog(word, word.wordId in state.excludedWordIds, !state.isWorking,
                                onToggle = { onAction(if (word.wordId in state.excludedWordIds)
                                    AppAction.UnmarkLearned(word.wordId) else AppAction.MarkLearned(word.wordId)) }) {
                                onAction(AppAction.DismissWord)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Opaque source pixels let frosted surfaces replace the sharp content beneath them. */
@Composable
private fun AppBackdrop() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Cream)
        drawRect(Brush.radialGradient(
            colors = listOf(Leaf.copy(alpha = .13f), Color.Transparent),
            center = Offset(size.width * .92f, size.height * .04f),
            radius = size.minDimension * 1.05f,
        ))
        drawRect(Brush.radialGradient(
            colors = listOf(Amber.copy(alpha = .10f), Color.Transparent),
            center = Offset(size.width * .04f, size.height * .92f),
            radius = size.minDimension * .9f,
        ))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .18f))))
    }
}

@Composable
private fun AppNavigation(selected: AppTab, rail: Boolean, onAction: (AppAction) -> Unit,
    modifier: Modifier = Modifier, backdrop: GraphicsLayer? = null, contentOrigin: Offset = Offset.Zero,
    pagePosition: Float = AppPages.indexOf(selected).coerceAtLeast(0).toFloat()) {
    val tabs = listOf(AppTab.LEARN, AppTab.MISTAKES, AppTab.PROFILE)
    val labels = listOf("学习", "易错本", "我的")
    val icons = listOf(Icons.Outlined.AutoStories, Icons.Outlined.Bookmarks, Icons.Outlined.PersonOutline)
    if (rail) {
        Box(modifier.fillMaxHeight().width(108.dp).clip(RectangleShape).border(1.dp, Line)) {
        FrostedCapsuleBackdrop(backdrop, contentOrigin, Paper)
        NavigationRail(
            containerColor = Color.Transparent,
            modifier = Modifier.fillMaxSize(),
        ) {
            Spacer(Modifier.height(30.dp))
            BrandMark(42.dp)
            Spacer(Modifier.height(48.dp))
            tabs.forEachIndexed { i, tab ->
                NavigationRailItem(
                    selected = selected == tab || (selected == AppTab.RECORDS && tab == AppTab.PROFILE), onClick = { onAction(AppAction.SelectTab(tab)) },
                    icon = { Icon(icons[i], labels[i], Modifier.size(20.dp)) }, label = { Text(labels[i], fontSize = 11.sp) },
                    colors = NavigationRailItemDefaults.colors(
                        indicatorColor = Mint, selectedIconColor = LeafDeep,
                        selectedTextColor = LeafDeep, unselectedIconColor = Muted, unselectedTextColor = Muted,
                    ),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text("WORDWISE", fontSize = 8.sp, letterSpacing = 1.sp, color = Muted, modifier = Modifier.padding(bottom = 24.dp))
        }
        }
    } else {
        // One frosted capsule outlines all three destinations; the foreground stays sharp.
        Box(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.widthIn(max = 380.dp).fillMaxWidth().height(58.dp)
                .shadow(14.dp, CircleShape, ambientColor = Ink.copy(alpha = .08f), spotColor = Ink.copy(alpha = .12f))
                .clip(CircleShape).border(1.dp, Paper.copy(alpha = .95f), CircleShape)
                .testTag("floatingNavigation")) {
                FrostedCapsuleBackdrop(backdrop, contentOrigin, Paper)
                BoxWithConstraints(Modifier.fillMaxSize().padding(5.dp)) {
                    val cellWidth = maxWidth / tabs.size
                    Box(Modifier.offset(x = cellWidth * pagePosition.coerceIn(0f, 2f))
                        .padding(horizontal = 3.dp).width(cellWidth - 6.dp).fillMaxHeight()
                        .clip(CircleShape).background(Brush.horizontalGradient(listOf(Ink, LeafDeep))))
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        tabs.forEachIndexed { index, tab ->
                            val amount = (1f - abs(pagePosition - index)).coerceIn(0f, 1f)
                            val active = selected == tab || (selected == AppTab.RECORDS && tab == AppTab.PROFILE)
                            TextButton(onClick = { onAction(AppAction.SelectTab(tab)) },
                                modifier = Modifier.weight(1f).fillMaxHeight().semantics { this.selected = active },
                                shape = CircleShape, contentPadding = PaddingValues(horizontal = 4.dp),
                                colors = ButtonDefaults.textButtonColors(contentColor = lerp(Muted, Paper, amount))) {
                                Icon(icons[index], null, Modifier.size(18.dp))
                                Spacer(Modifier.width(5.dp)); Text(labels[index], fontSize = 12.sp, maxLines = 1,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        }
    }

}

@Composable
private fun BrandMark(size: androidx.compose.ui.unit.Dp = 38.dp) {
    Image(painterResource(R.drawable.ic_wordwise_mark), contentDescription = null, modifier = Modifier.size(size))
}

@Composable
private fun PageHeader(title: String, eyebrow: String, wide: Boolean, subtitle: String? = null, trailing: @Composable (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = if (wide) 36.dp else 24.dp, vertical = if (wide) 22.dp else 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!wide) { BrandMark(40.dp); Spacer(Modifier.width(11.dp)) }
            Text(eyebrow, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        Spacer(Modifier.height(12.dp))
        Text(title, fontSize = if (wide) 28.sp else 25.sp, fontWeight = FontWeight.Bold, color = Ink,
            letterSpacing = (-0.3).sp)
        Box(Modifier.padding(top = 10.dp).size(width = 26.dp, height = 3.dp).clip(CircleShape).background(Amber))
        subtitle?.let { Text(it, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
    }
}

@Composable
private fun LearnScreen(state: AppUiState, wide: Boolean, onAction: (AppAction) -> Unit) {
    val calibrating = (state.placement.active || state.calibrationFeedback) && !state.reviewMode
    val latestWordId by rememberUpdatedState(state.currentWord?.wordId)
    val latestAnswer by rememberUpdatedState(state.answerIsKnown)
    val latestIsWorking by rememberUpdatedState(state.isWorking)
    val navigationInset = if (wide) 0.dp else NavigationClearance
    val density = LocalDensity.current
    var actionHeight by remember { mutableStateOf(52.dp) }
    val actionBackdrop = if (Build.VERSION.SDK_INT >= 31) rememberGraphicsLayer() else null
    var contentOrigin by remember { mutableStateOf(Offset.Zero) }
    Column(Modifier.fillMaxSize()) {
        LearnHeader(state, wide, calibrating)
        LearningModeSwitch(state, Modifier.padding(horizontal = if (wide) 24.dp else 16.dp), onAction)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.currentWord == null && state.isWorking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                state.sessionComplete || state.currentWord == null -> Box(Modifier.fillMaxSize().padding(bottom = navigationInset)) {
                    MessageState(
                        icon = { Icon(Icons.Outlined.CheckCircleOutline, null, Modifier.size(40.dp)) },
                        title = if (state.reviewMode) "暂时没有待复习的易错词" else "暂时没有可安排的单词",
                        body = if (state.reviewMode) "当前没有符合等级权重和复习时间的易错词。你可以返回无限学习，接触不同难度的词。" else "当前没有符合等级权重和复习时间的候选。今天的 ${state.stats.todayReviewed} 次练习和 ${formatStudyTime(state.stats.todayStudySeconds)} 学习用时已记录。",
                        actionLabel = "继续学习", onClick = { onAction(AppAction.ContinueLearning) },
                    )
                }
                else -> {
                    // Only the body is recorded. The opaque page backdrop makes the sampled
                    // button region replace sharp text underneath; controls never record themselves.
                    Box(Modifier.fillMaxSize().onGloballyPositioned { contentOrigin = it.positionInRoot() }
                        .drawWithContent {
                            if (actionBackdrop != null) {
                                actionBackdrop.record { this@drawWithContent.drawContent() }
                                drawLayer(actionBackdrop)
                            } else drawContent()
                        }) {
                        AppBackdrop()
                        AnimatedContent(
                            targetState = state.currentWord, contentKey = { it.wordId },
                            transitionSpec = {
                                (fadeIn(tween(170, 30)) + slideInVertically(tween(190)) { it / 80 }) togetherWith fadeOut(tween(90)) using null
                            },
                            modifier = Modifier.fillMaxSize(), label = "下一个单词",
                        ) { word ->
                            val isCurrent = word.wordId == state.currentWord.wordId
                            var retainedAnswer by remember(word.wordId) { mutableStateOf<Boolean?>(null) }
                            if (isCurrent) SideEffect { retainedAnswer = state.answerIsKnown }
                            val answer = if (isCurrent) state.answerIsKnown else retainedAnswer
                            val position = if (calibrating) state.placement.answered + if (answer == null) 1 else 0 else state.sessionPosition
                            val scrollInset = navigationInset + actionHeight + 16.dp
                            if (wide) {
                                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = scrollInset)) {
                                        WordCard(word, answer, position, calibrating, Modifier.fillMaxWidth())
                                    }
                                    Box(Modifier.weight(1f).fillMaxHeight()) {
                                        if (answer != null) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(end = 24.dp, bottom = scrollInset)) {
                                            WordDetails(word, showMeaning = false)
                                        } else StudyOverview(state)
                                    }
                                }
                            } else {
                                LazyColumn(Modifier.fillMaxSize().testTag("learningContent"), contentPadding = PaddingValues(bottom = scrollInset)) {
                                    item { WordCard(word, answer, position, calibrating, Modifier.fillMaxWidth()) }
                                    if (answer != null) item {
                                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                                            WordDetails(word, showMeaning = false)
                                        }
                                    } else item {
                                        StudyHint(state, calibrating)
                                    }
                                }
                            }
                        }
                    }
                    if (!wide) {
                        // A soft scrim keeps long examples readable while the floating
                        // action row sits on top; it fades instead of cutting the text.
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .height(navigationInset + actionHeight + 118.dp)
                            .background(Brush.verticalGradient(listOf(
                                Color.Transparent,
                                Cream.copy(alpha = .55f),
                                Cream.copy(alpha = .96f),
                                Cream,
                            ))))
                    }
                    Box(Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = navigationInset + 4.dp)
                        .widthIn(max = 380.dp).fillMaxWidth().onSizeChanged { actionHeight = with(density) { it.height.toDp() } }) {
                        AnswerControls(state.currentWord.wordId, state.answerIsKnown, !state.isWorking, state.canUndo,
                            actionBackdrop, contentOrigin) { action ->
                            if (!latestIsWorking && latestWordId == state.currentWord.wordId &&
                                (action !is AppAction.Answer || latestAnswer == null) &&
                                (action != AppAction.NextWord || latestAnswer != null)) onAction(action)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudyHint(state: AppUiState, calibrating: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
        if (calibrating) {
            Surface(shape = CardShape, color = Paper.copy(alpha = .64f), shadowElevation = 0.dp) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(Mint), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Lightbulb, null, Modifier.size(19.dp), tint = LeafDeep)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("凭第一反应判断就好", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        Text("不确定也没关系，校准会继续找到合适的起点。", fontSize = 11.sp,
                            lineHeight = 18.sp, color = Muted, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        } else {
            val rate = if (state.stats.todayReviewed == 0) "—" else
                "${state.stats.todayKnown * 100 / state.stats.todayReviewed}%"
            Surface(shape = CardShape, color = Paper.copy(alpha = .64f), shadowElevation = 0.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    HintMetric("今日练习", "${state.stats.todayReviewed} 次", Modifier.weight(1f))
                    Box(Modifier.width(1.dp).height(30.dp).background(Line))
                    HintMetric("认识率", rate, Modifier.weight(1f))
                    Box(Modifier.width(1.dp).height(30.dp).background(Line))
                    HintMetric("待复习", "${state.dueCount} 词", Modifier.weight(1f))
                }
            }
            Text("先想一想词义，再做判断。不认识的词会自动进入易错本。",
                fontSize = 11.sp, lineHeight = 18.sp, color = Muted,
                modifier = Modifier.padding(top = 10.dp, start = 4.dp))
        }
    }
}

@Composable
private fun HintMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1)
        Text(label, fontSize = 9.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun LearningModeSwitch(state: AppUiState, modifier: Modifier = Modifier, onAction: (AppAction) -> Unit) {
    val enabled = !state.placement.active && !state.calibrationFeedback
    val canChange = enabled && !state.isWorking
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Paper.copy(alpha = .72f))
            .border(1.dp, Line, shape)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ModeSegment(
            label = "无限学习",
            icon = Icons.Outlined.AllInclusive,
            selected = !state.reviewMode,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        ) {
            if (canChange) onAction(if (state.reviewMode) AppAction.ContinueLearning else AppAction.FeedbackTap)
        }
        ModeSegment(
            label = "易错词复习",
            icon = Icons.Outlined.Refresh,
            selected = state.reviewMode,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        ) {
            if (canChange) onAction(if (!state.reviewMode) AppAction.ReviewMistakes else AppAction.FeedbackTap)
        }
    }
}

@Composable
private fun ModeSegment(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val background by animateColorAsState(
        if (selected) Ink else Color.Transparent,
        tween(220, easing = FastOutSlowInEasing), label = "模式底色",
    )
    val foreground by animateColorAsState(
        if (selected) Paper else Muted,
        tween(220, easing = FastOutSlowInEasing), label = "模式文字",
    )
    val fontScale = LocalDensity.current.fontScale
    val compact = fontScale > 1.3f
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(background)
            .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) { this.selected = selected }
            .padding(horizontal = if (compact) 5.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (!compact) {
            Icon(icon, null, Modifier.size(17.dp), tint = foreground)
            Spacer(Modifier.width(6.dp))
        }
        Text(label, fontSize = if (compact) 12.sp else 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = foreground, textAlign = TextAlign.Center,
            modifier = if (compact) Modifier.fillMaxWidth() else Modifier)
    }
}

@Composable
private fun LearnHeader(state: AppUiState, wide: Boolean, calibrating: Boolean) {
    val placement = state.placement
    val progress by animateFloatAsState(
        (placement.answered.toFloat() / placement.total.coerceAtLeast(1)).coerceIn(0f, 1f),
        tween(450, easing = FastOutSlowInEasing), label = "校准进度",
    )
    val levelValue = state.stats.currentLevelLabel.substringBefore(" ").toFloatOrNull()
    Column(
        Modifier.fillMaxWidth().padding(
            horizontal = if (wide) 24.dp else 16.dp,
            vertical = 8.dp,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(40.dp)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("知词", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink, letterSpacing = (-0.2).sp)
                    Spacer(Modifier.width(7.dp))
                    Text("无限流", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = LeafDeep,
                        letterSpacing = .8.sp, modifier = Modifier.clip(CircleShape).background(Mint)
                            .padding(horizontal = 7.dp, vertical = 2.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 3.dp)) {
                    Text(if (calibrating) "水平校准" else "当前词汇水平",
                        fontSize = 10.sp, color = Muted)
                    Text(if (placement.active) "校准中" else state.stats.currentLevelLabel.takeIf { it.contains("级") } ?: "初步了解中",
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = LeafDeep,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                LabelPill(
                    if (calibrating) "${placement.answered} / ${placement.total}"
                    else if (state.reviewMode) "待复习 ${state.dueCount}" else "今日 ${state.stats.todayReviewed} 次",
                    if (calibrating) Icons.Outlined.Tune else if (state.reviewMode) Icons.Outlined.Refresh else Icons.Outlined.Schedule,
                    Mint,
                )
                if (state.stats.streakDays > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 5.dp)) {
                        Icon(Icons.Outlined.LocalFireDepartment, null, Modifier.size(12.dp), tint = Amber)
                        Spacer(Modifier.width(3.dp))
                        Text("连续 ${state.stats.streakDays} 天", fontSize = 9.sp, color = Sun, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        if (calibrating) {
            Spacer(Modifier.height(9.dp))
            Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Line)) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight()
                    .clip(CircleShape).background(Brush.horizontalGradient(listOf(Leaf, LeafDeep))))
            }
            Text(
                "先判断，再查看中文与例句 · 不计入学习统计",
                fontSize = 10.sp, lineHeight = 16.sp, color = Muted, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else if (levelValue != null) {
            Spacer(Modifier.height(9.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Line.copy(alpha = .8f))) {
                Box(Modifier.fillMaxWidth((levelValue / 10f).coerceIn(0f, 1f)).fillMaxHeight()
                    .clip(CircleShape).background(Brush.horizontalGradient(listOf(Amber, Leaf))))
            }
        }
    }
}

@Composable
private fun WordCard(word: WordUi, answer: Boolean?, position: Int, calibrating: Boolean, modifier: Modifier) {
    val cardShape = HeroShape
    val backgroundEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    val backgroundMiddle by animateColorAsState(
        targetValue = when (answer) {
            true -> Color(0xFFEAF7EF)
            false -> Color(0xFFFDECEE)
            null -> Color(0xFFEDF3FF)
        },
        animationSpec = tween(360, easing = backgroundEasing), label = "单词卡片背景中部",
    )
    val backgroundEnd by animateColorAsState(
        targetValue = when (answer) {
            true -> Color(0xFFD8EEDF)
            false -> Color(0xFFF7DCDC)
            null -> Color(0xFFDCE8FC)
        },
        animationSpec = tween(360, easing = backgroundEasing), label = "单词卡片背景底部",
    )
    Column(modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        .shadow(10.dp, cardShape, ambientColor = Ink.copy(alpha = .05f),
            spotColor = Ink.copy(alpha = .10f))
        .clip(cardShape)
        .background(Brush.linearGradient(listOf(Color.White, backgroundMiddle, backgroundEnd)))
        .border(1.dp, Color.White.copy(alpha = .9f), cardShape)
        .testTag("wordCard")
        .padding(horizontal = 24.dp, vertical = 22.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.clip(CircleShape).background(Paper.copy(alpha = .8f)).padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (answer == false) Coral else Leaf))
                Spacer(Modifier.width(6.dp))
                Text(word.difficulty, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Muted, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Text("${if (calibrating) "校准" else "WORD"} ${position.coerceAtLeast(1).toString().padStart(2, '0')}",
                fontSize = 10.sp, letterSpacing = 1.6.sp, color = Muted, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(14.dp))
        AutoSizedWord(word.word, 1f)
        WordPronunciation(word, Modifier.padding(top = 8.dp))
        if (answer != null && word.meaning.isNotBlank()) {
            Spacer(Modifier.height(18.dp))
            WordMeaningPanel(word)
        }
    }
}

/** Long words shrink until they fit on a single line; they never wrap. */
@Composable
private fun AutoSizedWord(text: String, scale: Float) {
    var fontSize by remember(text) { mutableFloatStateOf(initialWordFontSize(text.length)) }
    Text(
        text = text,
        fontFamily = FontFamily.Serif,
        fontSize = fontSize.sp,
        fontWeight = FontWeight.Bold,
        color = Ink,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        letterSpacing = (-0.5).sp,
        modifier = Modifier.fillMaxWidth().scale(scale),
        onTextLayout = { result ->
            if (result.didOverflowWidth && fontSize > 12f) {
                fontSize = (fontSize - 2f).coerceAtLeast(12f)
            }
        },
    )
}

private fun initialWordFontSize(length: Int): Float = when {
    length <= 8 -> 40f
    length <= 12 -> 35f
    length <= 16 -> 31f
    length <= 22 -> 25f
    length <= 30 -> 20f
    else -> 16f
}

/** The Chinese explanation lives inside the card so it is visible without scrolling. */
@Composable
private fun WordMeaningPanel(word: WordUi) {
    var expanded by remember(word.wordId) { mutableStateOf(false) }
    var overflowed by remember(word.wordId, expanded) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(18.dp))
            .background(Paper.copy(alpha = .62f))
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(CircleShape).background(Leaf))
        Spacer(Modifier.width(11.dp))
        Column {
            Text("中文释义", fontSize = 9.sp, letterSpacing = 1.2.sp, color = Muted, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(word.meaningWithPartOfSpeech(), fontSize = 16.sp, lineHeight = 26.sp,
                color = Ink, fontWeight = FontWeight.Medium,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded && it.hasVisualOverflow) overflowed = true })
            if (overflowed || expanded) {
                TextButton(onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 36.dp)) {
                    Text(if (expanded) "收起释义" else "展开全部释义", fontSize = 11.sp,
                        color = LeafDeep, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** ECDICT already embeds the part of speech in most Chinese meanings, so show it as-is. */
private fun WordUi.meaningWithPartOfSpeech(): String =
    meaning.ifBlank { "尚未收录中文释义" }.trim()

@Composable
private fun WordPronunciation(word: WordUi, modifier: Modifier = Modifier) {
    val accent = LocalPronunciationAccent.current
    val preferred = if (accent == PronunciationAccent.US) word.americanIpa else word.britishIpa
    val alternate = if (accent == PronunciationAccent.US) word.britishIpa else word.americanIpa
    val reading = PronunciationDisplay.primary(preferred.ifBlank { alternate })
    if (reading.isBlank()) return
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (preferred.isNotBlank()) {
            if (accent == PronunciationAccent.US) "美式音标" else "英式音标"
        } else if (accent == PronunciationAccent.US) "英式音标（美式待补）" else "美式音标（英式待补）",
            fontSize = 10.sp, color = Muted, letterSpacing = .5.sp)
        Text(reading, fontFamily = FontFamily.SansSerif, fontSize = 18.sp,
            color = LeafDeep, lineHeight = 27.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp))
    }
}

/** Shared by the navigation and every learning action; only the background is blurred. */
@Composable
internal fun BoxScope.FrostedCapsuleBackdrop(
    backdrop: GraphicsLayer?,
    contentOrigin: Offset,
    tint: Color,
) {
    var sampleOrigin by remember { mutableStateOf(Offset.Zero) }
    if (backdrop != null) Canvas(Modifier.matchParentSize()
        // Sample beyond the final capsule, then clip the finished blur in its parent.
        .layout { measurable, constraints ->
            val inset = 36.dp.roundToPx()
            val sample = measurable.measure(Constraints.fixed(
                constraints.maxWidth + inset * 2, constraints.maxHeight + inset * 2))
            layout(constraints.maxWidth, constraints.maxHeight) { sample.place(-inset, -inset) }
        }
        .onGloballyPositioned { sampleOrigin = it.positionInRoot() }
        .blur(12.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)) {
        drawRect(Cream)
        val relative = sampleOrigin - contentOrigin
        translate(-relative.x, -relative.y) { drawLayer(backdrop) }
    }
    // Android 8–11 keeps the same light tint without a GPU blur pass.
    Box(Modifier.matchParentSize().background(tint.copy(alpha = .25f)))
}

@Composable
private fun GlassActionButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier,
    backdrop: GraphicsLayer?,
    contentOrigin: Offset,
    tint: Color = Paper,
    contentColor: Color = Forest,
    filled: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    if (filled) {
        Button(onClick = onClick, enabled = enabled,
            modifier = modifier.heightIn(min = 48.dp), shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = tint, contentColor = contentColor,
                disabledContainerColor = tint.copy(alpha = .28f),
                disabledContentColor = contentColor.copy(alpha = .55f)),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp,
                disabledElevation = 0.dp), content = content)
        return
    }
    Box(modifier.clip(CircleShape)) {
        FrostedCapsuleBackdrop(backdrop, contentOrigin, tint)
        OutlinedButton(onClick = onClick, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = CircleShape,
            border = androidx.compose.foundation.BorderStroke(1.dp, contentColor.copy(alpha = .38f)),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent, contentColor = contentColor,
                disabledContentColor = contentColor.copy(alpha = .45f)), content = content)
    }
}

@Composable
private fun AnswerControls(
    wordId: String,
    answer: Boolean?,
    enabled: Boolean,
    canUndo: Boolean,
    backdrop: GraphicsLayer?,
    contentOrigin: Offset,
    onAction: (AppAction) -> Unit,
) {
    // A new word and a revealed answer each get a fresh protection window. Early
    // taps are ignored, never queued: a double tap must not answer then skip a card.
    var guardedActionReady by remember(wordId, answer) { mutableStateOf(false) }
    LaunchedEffect(wordId, answer) {
        delay(300)
        guardedActionReady = true
    }
    var submitted by remember(wordId, answer) { mutableStateOf(false) }
    LaunchedEffect(wordId, answer, submitted) {
        // The local lock prevents double taps, but must not remain stuck if the
        // upstream state rejects a stale click without changing the word or answer.
        if (submitted) { delay(300); submitted = false }
    }
    val canSubmit = enabled && !submitted
    fun submit(action: AppAction) {
        val guarded = (action is AppAction.Answer && action.known) || action == AppAction.NextWord
        if (enabled && !submitted && (!guarded || guardedActionReady)) {
            submitted = true
            onAction(action)
        }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("learningActions"),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (answer == null) {
            GlassActionButton(onClick = { submit(AppAction.Answer(false)) }, enabled = canSubmit,
                modifier = Modifier.weight(1f), backdrop = backdrop, contentOrigin = contentOrigin,
                tint = Color(0xFFFDF0F1), contentColor = Coral) {
                Icon(Icons.Outlined.Close, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp))
                Text("不认识", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            GlassActionButton(onClick = { submit(AppAction.Answer(true)) }, enabled = canSubmit && guardedActionReady,
                modifier = Modifier.weight(1f), backdrop = backdrop, contentOrigin = contentOrigin,
                tint = Ink, contentColor = Paper, filled = true) {
                Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp))
                Text("认识", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            if (canUndo) FilledTonalButton(
                onClick = { submit(AppAction.UndoAnswer) }, enabled = canSubmit,
                modifier = Modifier.widthIn(min = 76.dp).heightIn(min = 54.dp),
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Mint, contentColor = Ink,
                    disabledContainerColor = Mint.copy(alpha = .5f),
                    disabledContentColor = Ink.copy(alpha = .45f)),
            ) {
                Text("反悔", fontSize = 14.sp)
            }
            GlassActionButton(onClick = { submit(AppAction.NextWord) }, enabled = canSubmit && guardedActionReady,
                modifier = Modifier.weight(1f).heightIn(min = 54.dp), backdrop = backdrop, contentOrigin = contentOrigin,
                tint = Ink, contentColor = Paper, filled = true) {
                Text("下一个单词", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false), textAlign = TextAlign.Center)
                Spacer(Modifier.width(7.dp)); Icon(Icons.Outlined.ArrowForward, null, Modifier.size(19.dp))
            }
        }
    }
}

@Composable
private fun StudyOverview(state: AppUiState) {
    if (state.placement.active && !state.reviewMode) {
        CalibrationOverview(state.placement)
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp)) {
        Text("你的学习节奏", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("不设每日上限，按自己的节奏继续。", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MiniStat("今日练习", animatedCount(state.stats.todayReviewed).toString(), "次", Modifier.weight(1f))
            MiniStat("待复习", state.dueCount.toString(), "词", Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MiniStat("今日接触", state.stats.todayLearnedWords.toString(), "词", Modifier.weight(1f))
            MiniStat("今日用时", formatStudyTime(state.stats.todayStudySeconds), "", Modifier.weight(1f))
        }
        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = Line)
        Spacer(Modifier.height(24.dp))
        Text("适合你的单词，会自然出现", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(top = 18.dp))
        Text("新词与当前水平相差不超过两级，按 0.02 × 实际等级差的绝对值加权，最低为 0.002：同级仍有机会，相差半级为 0.01、一级为 0.02、两级为 0.04。复习使用同一距离权重，并优先照顾易错和逾期词。新词认识后按权重加分，复习累计两次认识、首次学会时再奖励一次。两类候选都可用时，最多连续 3 个新词或 2 个复习词。认识后冷却一小时，学会后不再出题。", color = Muted, fontSize = 14.sp, lineHeight = 25.sp, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.weight(1f))
        Text("TAKE YOUR TIME. MAKE IT STICK.", fontSize = 9.sp, letterSpacing = 1.5.sp, color = Muted, modifier = Modifier.padding(top = 32.dp))
    }
}

@Composable
private fun CalibrationOverview(placement: PlacementUi) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp)) {
        LabelPill("独立水平校准", Icons.Outlined.Tune, Sage)
        Text("用不同难度的词，\n了解你的学习起点。", fontSize = 24.sp, lineHeight = 37.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 24.dp))
        Text("从 3 级起步，多次认识后才逐步提高难度，不认识时会回到更合适的难度。${placement.total} 词用于了解学习起点，不代表真实词汇量。", color = Muted,
            fontSize = 14.sp, lineHeight = 25.sp, modifier = Modifier.padding(top = 16.dp))
        HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 26.dp))
        Text("已完成 ${placement.answered} / ${placement.total}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Forest)
        Text("校准答案和用时不计入学习统计。每题判断后可查看中文和例句，点击下一词继续。", color = Muted,
            fontSize = 13.sp, lineHeight = 24.sp, modifier = Modifier.padding(top = 14.dp))
        Text("之后会根据日常学习反馈逐步调整水平，无需再次校准。", color = Muted,
            fontSize = 13.sp, lineHeight = 24.sp, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun LevelSummary(stats: StatsUi) {
    val levelValue = stats.currentLevelLabel.substringBefore(" ").toFloatOrNull()
    Surface(shape = CardShape, color = Paper, shadowElevation = 2.dp,
        modifier = Modifier.testTag("level-summary")) {
        Column(Modifier.fillMaxWidth().padding(22.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text("当前词汇水平", fontSize = 13.sp, color = Muted,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f).alignByBaseline().testTag("level-title"))
                Text(stats.currentLevelLabel, fontSize = 25.sp, color = Ink,
                    fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp,
                    modifier = Modifier.alignByBaseline().testTag("level-score"))
            }
            if (stats.currentLevelStage.isNotBlank()) Text(stats.currentLevelStage,
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = LeafDeep,
                modifier = Modifier.padding(top = 12.dp))
            if (levelValue != null) LinearProgressIndicator(
                progress = { (levelValue / 10f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(5.dp).clip(CircleShape),
                color = Leaf, trackColor = Mint,
            )
            if (stats.currentLevelDescription.isNotBlank()) Text(stats.currentLevelDescription,
                fontSize = 13.sp, lineHeight = 23.sp, color = Muted,
                modifier = Modifier.padding(top = 12.dp))
            Text("词库学习带估计，非考试等级。", fontSize = 11.sp, color = Muted,
                modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun WordDetails(word: WordUi, showMeaning: Boolean = true) {
    if (showMeaning && word.meaning.isNotBlank()) {
        Text(word.meaningWithPartOfSpeech(), color = Ink, fontSize = 17.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
    }
    val examples = word.examples.filter { it.english.isNotBlank() }
    if (examples.isNotEmpty()) DetailSection("在句子里记住", Icons.Outlined.FormatQuote) {
        examples.forEachIndexed { index, example ->
            if (index > 0) Spacer(Modifier.height(10.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = Cream.copy(alpha = .75f)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(example.english, fontSize = 15.sp, lineHeight = 25.sp, color = Ink,
                        fontWeight = FontWeight.Medium)
                    if (example.chinese.isNotBlank()) Text(example.chinese, fontSize = 13.sp,
                        lineHeight = 22.sp, color = Muted, modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
    }
    if (word.wordForms.any { it.text.isNotBlank() }) DetailSection("词形变化", Icons.Outlined.SwapVert) { NoteList(word.wordForms) }
    if (word.collocations.any { it.text.isNotBlank() }) DetailSection("搭配与短语", Icons.Outlined.Link) { NoteList(word.collocations) }
    if (word.derivatives.any { it.text.isNotBlank() }) DetailSection("派生与词族", Icons.Outlined.AccountTree) { NoteList(word.derivatives) }
    if (word.synonyms.any { it.text.isNotBlank() }) DetailSection("同义与近义词", Icons.Outlined.CompareArrows) {
        NoteList(word.synonyms)
    }
    if (word.confusables.any { it.text.isNotBlank() }) DetailSection("易错词", Icons.Outlined.CompareArrows) {
        NoteList(word.confusables)
    }
    if (word.similarWords.any { it.text.isNotBlank() }) DetailSection("形近词", Icons.Outlined.Spellcheck) {
        NoteList(word.similarWords)
    }
}

@Composable
private fun DetailSection(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        HorizontalDivider(color = Line)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp, bottom = 10.dp)) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(Mint), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(13.dp), tint = LeafDeep)
            }
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = LeafDeep)
        }
        content()
    }
}

@Composable
private fun NoteList(notes: List<WordNoteUi>) {
    notes.filter { it.text.isNotBlank() }.forEachIndexed { index, note ->
        if (index > 0) Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.padding(top = 7.dp).size(5.dp).clip(CircleShape).background(Amber))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                val accent = LocalPronunciationAccent.current
                val reading = PronunciationDisplay.related(note.americanIpa, note.britishIpa, accent)
                    .ifBlank { if (note.pronunciationExpected) "音标待补" else "" }
                Text(buildAnnotatedString {
                    append(note.text.trim())
                    if (reading.isNotBlank()) withStyle(SpanStyle(color = Muted,
                        fontSize = 13.sp, fontWeight = FontWeight.Normal)) {
                        append("  "); append(reading)
                    }
                }, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink, lineHeight = 24.sp)
                if (note.meaning.isNotBlank()) Text(note.meaning.trim(), fontSize = 13.sp, color = Muted, lineHeight = 22.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

@Composable
private fun MistakesScreen(state: AppUiState, wide: Boolean, onAction: (AppAction) -> Unit) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val feedbackTap = LocalFeedbackTap.current
    val filtered = remember(state.mistakes, filter) {
        state.mistakes.filter { filter == 0 || if (filter == 1) it.knownCount == 0 else it.knownCount > 0 }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("易错本", "YOUR WORD COLLECTION", wide, "${state.mistakes.size} 个待巩固单词") {
            if (state.mistakes.isNotEmpty()) {
                TextButton(onClick = { feedbackTap(); onAction(AppAction.ReviewMistakes) }) {
                    Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("复习全部", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        val pad = if (wide) 24.dp else 16.dp
        if (state.mistakes.isEmpty()) Box(Modifier.weight(1f)) {
            MessageState(
                { Icon(Icons.Outlined.Bookmarks, null, Modifier.size(38.dp)) }, "易错本还是空白的", "学习时选择「不认识」的单词会自动收录在这里。",
                "去学习", { onAction(AppAction.SelectTab(AppTab.LEARN)) },
                bottomClearance = if (wide) 0.dp else NavigationClearance,
            )
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = pad), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全部", "尚未认识", "认识过一次").forEachIndexed { index, text ->
                    FilterChip(selected = filter == index, onClick = { feedbackTap(); filter = index },
                        label = { Text(text, fontSize = 12.sp) },
                        shape = CircleShape,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Ink, selectedLabelColor = Paper,
                            containerColor = Paper.copy(alpha = .8f), labelColor = Muted))
                }
            }
            Text("点开单词详情，可标记已学会并随时撤销", fontSize = 11.sp, color = Muted,
                modifier = Modifier.padding(horizontal = pad, vertical = 8.dp))
            if (filtered.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("暂无符合条件的单词", color = Muted, fontSize = 14.sp)
            } else LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = pad),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = if (wide) 8.dp else NavigationClearance + 8.dp)) {
                items(filtered, key = { it.word.wordId }) { progress ->
                    MistakeCard(progress, Modifier.fillMaxWidth()) {
                        onAction(AppAction.OpenWord(progress.word))
                    }
                }
            }
        }
    }
}

@Composable
private fun MistakeCard(progress: WordProgressUi, modifier: Modifier, onClick: () -> Unit) {
    val percent = progress.masteryPercent.coerceIn(0, 100)
    val ringColor = when {
        percent < 40 -> Coral
        percent < 60 -> Amber
        else -> Leaf
    }
    Surface(onClick = onClick, modifier = modifier, color = Paper,
        shape = RoundedCornerShape(20.dp), shadowElevation = 2.dp) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(progress.word.word, fontFamily = FontFamily.Serif, fontSize = 22.sp, lineHeight = 26.sp,
                        fontWeight = FontWeight.Bold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    Text(progress.word.difficulty, fontSize = 9.sp, color = Muted, maxLines = 1,
                        modifier = Modifier.clip(CircleShape).background(Cream).padding(horizontal = 7.dp, vertical = 3.dp))
                }
                val reading = PronunciationDisplay.related(progress.word.americanIpa,
                    progress.word.britishIpa, LocalPronunciationAccent.current)
                if (reading.isNotBlank()) Text(reading, fontSize = 12.sp, lineHeight = 19.sp,
                    color = LeafDeep, modifier = Modifier.padding(top = 3.dp))
                Text("认识 ${progress.knownCount} 次 · 不认识 ${progress.unknownCount} 次",
                    fontSize = 10.sp, lineHeight = 16.sp, color = Muted, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(9.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Line)) {
                    Box(Modifier.fillMaxWidth(percent / 100f).fillMaxHeight().clip(CircleShape).background(ringColor))
                }
            }
            Spacer(Modifier.width(16.dp))
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { percent / 100f }, modifier = Modifier.size(46.dp),
                    strokeWidth = 4.dp, color = ringColor, trackColor = Line)
                Text("$percent%", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun StudyRecords(stats: StatsUi) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            run {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard("已学单词", stats.learnedWords, "词", Icons.Outlined.AutoStories, Modifier.weight(1f))
                    StatCard("已掌握", stats.masteredWords, "词", Icons.Outlined.CheckCircleOutline, Modifier.weight(1f))
                }
            }
            run {
                Surface(shape = CardShape, color = Paper) {
                    Column(Modifier.padding(24.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Schedule, null, Modifier.size(20.dp), tint = Forest)
                            Spacer(Modifier.width(9.dp)); Text("专注学习时间", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StudyTimeValue("今日用时", stats.todayStudySeconds, Modifier.weight(1f))
                            StudyTimeValue("累计用时", stats.totalStudySeconds, Modifier.weight(1f))
                        }
                        HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 18.dp))
                        Text("今日接触 ${stats.todayLearnedWords} 个不同单词 · ${stats.todayReviewed} 次练习", fontSize = 12.sp, lineHeight = 21.sp, color = Forest)
                        Text("仅统计前台学习页的活跃用时；切到后台，或连续 60 秒没有操作时暂停。", fontSize = 11.sp, lineHeight = 20.sp, color = Muted, modifier = Modifier.padding(top = 9.dp))
                    }
                }
            }
            run {
                Surface(shape = CardShape, color = Paper) {
                    Column(Modifier.padding(24.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text("最近 7 天", fontSize = 18.sp, fontWeight = FontWeight.Bold); Text("每次回答，都是一次练习", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)) }
                            Text("学习次数", fontSize = 10.sp, color = Muted)
                        }
                        Spacer(Modifier.height(24.dp))
                        WeekChart(stats.week)
                        if (stats.week.all { it.reviewed == 0 }) Text("开始学习后，你的足迹会出现在这里。", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 18.dp))
                    }
                }
            }
            run {
                Row(Modifier.fillMaxWidth().padding(bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MiniStat("累计练习", stats.totalReviewed.toString(), "次", Modifier.weight(1f))
                    MiniStat("学习天数", stats.learningDays.toString(), "天", Modifier.weight(1f))
                    val rate = if (stats.todayReviewed == 0) "—" else "${animatedCount(stats.todayKnown * 100 / stats.todayReviewed)}%"
                    MiniStat("今日认识率", rate, "", Modifier.weight(1f))
                }
            }
    }
}

@Composable
private fun WeekChart(days: List<StudyDayUi>) {
    val values = if (days.isEmpty()) List(7) { StudyDayUi("—", 0, 0) } else days.takeLast(7)
    val max = values.maxOf { it.reviewed }.coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(160.dp), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Bottom) {
        values.forEachIndexed { index, day ->
            var entered by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { entered = true }
            val fraction by animateFloatAsState(
                if (entered) day.reviewed.toFloat() / max else 0f,
                tween(550, delayMillis = index * 35, easing = FastOutSlowInEasing), label = "一周学习柱图",
            )
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(animatedCount(day.reviewed).toString(), fontSize = 10.sp,
                    color = if (day.reviewed > 0) Ink else Muted, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.height(110.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.widthIn(max = 40.dp).fillMaxWidth(.68f)
                        .height(if (day.reviewed == 0) 4.dp else (fraction * 110).dp.coerceAtLeast(4.dp))
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                        .background(
                            when {
                                day.reviewed == 0 -> Brush.verticalGradient(listOf(Line, Line))
                                index == values.lastIndex -> Brush.verticalGradient(listOf(Leaf, LeafDeep))
                                else -> Brush.verticalGradient(listOf(Leaf.copy(alpha = .55f), Leaf.copy(alpha = .28f)))
                            }
                        ))
                }
                Spacer(Modifier.height(11.dp))
                Text(day.label, color = Muted, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

/** Keep keyboard/accessibility focus above the floating navigation without clipping the page. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FocusAboveNavigation(wide: Boolean, content: @Composable () -> Unit) {
    val parent = LocalBringIntoViewSpec.current
    val density = LocalDensity.current
    val clearance = with(density) { if (wide) 0f else NavigationClearance.toPx() }
    val spec = remember(parent, clearance) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                parent.calculateScrollDistance(offset, size, (containerSize - clearance).coerceAtLeast(1f))
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

@Composable
private fun ProfileScreen(profile: ProfileUi, stats: StatsUi, feedback: FeedbackUi, wide: Boolean, isWorking: Boolean, onAction: (AppAction) -> Unit) {
    var edit by remember { mutableStateOf(false) }
    val feedbackTap = LocalFeedbackTap.current
    Column(Modifier.fillMaxSize()) {
        PageHeader("我的", "YOUR WORDWISE", wide)
        FocusAboveNavigation(wide) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 36.dp else 24.dp).padding(bottom = if (wide) 0.dp else NavigationClearance), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Surface(shape = HeroShape, color = Paper, shadowElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()
                        .background(Brush.linearGradient(listOf(Paper, Mint.copy(alpha = .55f), Paper)))
                        .padding(22.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                                Box(Modifier.fillMaxSize().clip(CircleShape).background(
                                    Brush.linearGradient(listOf(Leaf, LeafDeep))))
                                Text(ProfileNames.initial(profile.displayName), fontSize = 27.sp,
                                    fontWeight = FontWeight.SemiBold, color = Paper)
                                Box(Modifier.align(Alignment.BottomEnd).size(16.dp).clip(CircleShape)
                                    .background(Paper).padding(3.dp).clip(CircleShape).background(Amber))
                            }
                            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                Text(profile.displayName, fontSize = 23.sp, fontWeight = FontWeight.Bold,
                                    color = Ink, letterSpacing = (-0.2).sp)
                                Text(if (profile.createdAtLabel.isBlank()) "你的专属学习空间" else "从 ${profile.createdAtLabel} 开始积累",
                                    fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 5.dp))
                            }
                            IconButton(onClick = { feedbackTap(); edit = true },
                                modifier = Modifier.size(42.dp).clip(CircleShape).background(Paper.copy(alpha = .9f))) {
                                Icon(Icons.Outlined.Edit, "修改资料", tint = LeafDeep, modifier = Modifier.size(18.dp))
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Paper.copy(alpha = .62f))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("已学 ${stats.learnedWords}", fontSize = 11.sp, color = Ink, fontWeight = FontWeight.SemiBold)
                            Text(" 词", fontSize = 9.sp, color = Muted)
                            Spacer(Modifier.weight(1f))
                            Text("已掌握 ${stats.masteredWords}", fontSize = 11.sp, color = Ink, fontWeight = FontWeight.SemiBold)
                            Text(" 词", fontSize = 9.sp, color = Muted)
                            Spacer(Modifier.weight(1f))
                            Text("连续 ${stats.streakDays}", fontSize = 11.sp, color = Ink, fontWeight = FontWeight.SemiBold)
                            Text(" 天", fontSize = 9.sp, color = Muted)
                        }
                    }
                }
                LevelSummary(stats)
                SettingsPanel(feedback, onAction)
                Surface(shape = CardShape, color = Paper, shadowElevation = 1.dp) {
                    Column(Modifier.padding(22.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(34.dp).clip(CircleShape).background(Mint), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.AllInclusive, null, tint = LeafDeep, modifier = Modifier.size(19.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("学习方式", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                                Text("无限流 · 无每日上限", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 3.dp))
                            }
                        }
                        HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 16.dp))
                        Text("首次从 3 级起步，用最多 20 词逐步校准。新词限定在当前水平上下两级内，权重为 0.02 × 实际等级差的绝对值，最低 0.002，同级词也会出现。复习使用同一公式，并考虑错误率与逾期时间；超出两级的旧词仍按实际差值复习。新词认识、复习首次学会时按距离权重加分，累计两次认识后退出出题。首次新词不认识时仍会修正水平；认识后冷却一小时，不认识的词 5 分钟后可复习。单词难度不等同 CEFR 或考试成绩。", fontSize = 12.sp, lineHeight = 22.sp, color = Muted)
                    }
                }
                StudyRecords(stats)
                Surface(shape = CardShape, color = Paper, shadowElevation = 1.dp) {
                    Column(Modifier.padding(22.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(34.dp).clip(CircleShape).background(Cream), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Lock, null, tint = LeafDeep, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text("学习只属于你", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Ink)
                        }
                        Text("无需注册或登录。你的昵称、学习记录和易错词都保存在当前设备中。", color = Muted, fontSize = 13.sp, lineHeight = 24.sp, modifier = Modifier.padding(top = 14.dp))
                        Text("卸载应用或清除应用数据会同时移除这些记录。", color = Muted, fontSize = 11.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    BrandMark(46.dp)
                    Text("知词", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    Text("WORDWISE · INFINITE FLOW", fontSize = 9.sp, letterSpacing = 1.7.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
                    Text("本地自适应学习 · v2.0.9", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
    }
    if (edit) ProfileDialog(profile, isWorking, { feedbackTap(); edit = false }) { name ->
        if (!isWorking) { onAction(AppAction.SaveProfile(name, profile.dailyGoal)); edit = false }
    }
}

@Composable
private fun ProfileDialog(profile: ProfileUi, isWorking: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(profile.displayName) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("修改昵称", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = ProfileNames.limit(it) },
                label = { Text("你的昵称") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank() && !isWorking) { Text("保存", fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }, containerColor = Paper, shape = HeroShape,
    )
}

@Composable
private fun WordDialog(word: WordUi, learned: Boolean, enabled: Boolean, onToggle: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = HeroShape, color = Paper, shadowElevation = 12.dp,
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(.92f).fillMaxHeight(.88f).testTag("word-dialog")) {
            Column {
                Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 38.dp, height = 4.dp).clip(CircleShape).background(Line))
                }
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("单词详情", fontSize = 12.sp, color = Muted, fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                    Surface(onClick = onDismiss, shape = CircleShape, color = Cream) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Close, "关闭单词详情", Modifier.size(18.dp), tint = Ink)
                        }
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp)
                        .testTag("word-dialog-heading"), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(word.word, fontFamily = FontFamily.Serif, fontSize = 38.sp, color = Ink,
                            fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        Text(word.difficulty, fontSize = 10.sp, color = Muted,
                            modifier = Modifier.padding(top = 8.dp).clip(CircleShape)
                                .background(Cream).padding(horizontal = 9.dp, vertical = 4.dp))
                    }
                    WordPronunciation(word, Modifier.padding(bottom = 20.dp).testTag("word-dialog-ipa"))
                    WordDetails(word)
                    if (learned) {
                        OutlinedButton(onClick = onToggle, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().padding(top = 24.dp).heightIn(min = 50.dp)
                                .testTag("learned-${word.wordId}"), shape = CircleShape) {
                            Text(if (learned) "按错了" else "已学会")
                        }
                    } else {
                        Button(onClick = onToggle, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().padding(top = 24.dp).heightIn(min = 50.dp)
                                .testTag("learned-${word.wordId}"), shape = CircleShape) {
                            Text(if (learned) "按错了" else "已学会")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LabelPill(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, background: Color) {
    Row(Modifier.clip(CircleShape).background(background).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(12.dp), tint = LeafDeep)
        Spacer(Modifier.width(5.dp))
        Text(text, color = LeafDeep, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun MiniStat(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(Paper.copy(alpha = .9f)).padding(15.dp)) {
        Text(label, fontSize = 10.sp, color = Muted, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 10.dp)) {
            Text(value, fontSize = if (value.length > 7) 16.sp else if (value.length > 5) 20.sp else 24.sp,
                fontWeight = FontWeight.Bold, color = Ink)
            if (unit.isNotBlank()) Text(unit, fontSize = 10.sp, color = Muted,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        }
    }
}

@Composable
private fun StatCard(label: String, value: Int, unit: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Column(modifier.clip(CardShape).background(Paper).padding(21.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = Leaf)
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 18.dp)) {
            Text(animatedCount(value).toString(), color = Ink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(unit, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(start = 6.dp, bottom = 7.dp))
        }
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
private fun MessageState(icon: @Composable () -> Unit, title: String, body: String, actionLabel: String, onClick: () -> Unit,
    bottomClearance: androidx.compose.ui.unit.Dp = 0.dp) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 32.dp, end = 32.dp, top = 40.dp, bottom = 40.dp + bottomClearance), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(Brush.linearGradient(listOf(Mint, Paper))))
            Box(Modifier.fillMaxSize().clip(CircleShape).border(1.dp, Line, CircleShape))
            CompositionLocalProvider(LocalContentColor provides LeafDeep) { icon() }
        }
        Text(title, color = Ink, fontSize = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 26.dp), letterSpacing = (-0.2).sp)
        Text(body, color = Muted, fontSize = 14.sp, lineHeight = 25.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 380.dp).padding(top = 12.dp))
        Button(onClick = onClick, shape = CircleShape, modifier = Modifier.padding(top = 28.dp).widthIn(min = 168.dp).heightIn(min = 52.dp)) { Text(actionLabel) }
    }
}

@Composable
private fun LoadingScreen() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        BrandMark(72.dp)
        Spacer(Modifier.height(24.dp))
        Text("为你准备第一个单词", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Text("词汇和学习记录都在本地，安心开始。", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 10.dp))
        CircularProgressIndicator(modifier = Modifier.padding(top = 28.dp).size(24.dp), strokeWidth = 2.dp,
            color = Leaf, trackColor = Line)
    }
}

/** Animate changes and initial statistics with the platform motion-duration scale. */
@Composable
private fun animatedCount(value: Int): Int {
    var target by remember { mutableIntStateOf(0) }
    LaunchedEffect(value) { target = value }
    val count by animateIntAsState(target, tween(600, easing = FastOutSlowInEasing), label = "统计数字")
    return count
}

private fun formatStudyTime(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3_600
    val minutes = safe % 3_600 / 60
    val remaining = safe % 60
    return when {
        hours > 0 -> "${hours}小时${minutes}分"
        minutes > 0 -> "${minutes}分${remaining}秒"
        else -> "${remaining}秒"
    }
}

@Composable
private fun StudyTimeValue(label: String, seconds: Long, modifier: Modifier = Modifier) {
    val value = formatStudyTime(seconds)
    Column(modifier) {
        Text(label, fontSize = 11.sp, color = Muted)
        Text(value, fontSize = if (value.length > 8) 18.sp else 23.sp, fontWeight = FontWeight.SemiBold, color = Forest, modifier = Modifier.padding(top = 8.dp))
    }
}
