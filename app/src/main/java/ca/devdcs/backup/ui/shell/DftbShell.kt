package ca.devdcs.backup.ui.shell

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import ca.devdcs.backup.R
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.ui.licenses.ExternalLinks
import ca.devdcs.backup.ui.navigation.DftbDestination
import ca.devdcs.backup.ui.navigation.DftbNavHost
import kotlinx.coroutines.delay

/**
 * The app shell: adaptive top-level navigation around [DftbNavHost] plus app-level event handling.
 *
 * - Compact windows show a bottom [ShellBottomBar] on section roots only; medium/expanded windows
 * show a [ShellRail] on every post-onboarding screen; nothing is drawn during onboarding.
 * - The NavHost keeps a single call site whatever the chrome, so a rotation or fold that switches
 * bar ↔ rail never rebuilds the screens or loses their saved state.
 * - Notification deep links ([DeepLinkInbox]) are resolved and navigated once onboarding is done.
 * - Install confirmations the app restorer waits on are launched one at a time while resumed.
 */
@Composable
fun DftbShell(startDestination: DftbDestination, viewModel: ShellViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStack.collectAsStateWithLifecycle()
    val routes = remember(backStack) { backStack.map { it.destination.route } }
    val currentRoute = routes.lastOrNull { it != null }
    val width = rememberShellWidth()
    val chrome = ShellRoutes.chromeFor(width, currentRoute)
    val selected = ShellRoutes.selectedTopLevel(routes)
    val snackbarHostState = remember { SnackbarHostState() }
    val onSelect: (TopLevelDestination) -> Unit = { item -> navController.onTopLevelSelected(item, currentRoute, selected) }

    DeepLinkEffect(viewModel, navController, currentRoute)
    InstallPromptEffect(viewModel, snackbarHostState)

    Row(modifier = Modifier.fillMaxSize()) {
        if (chrome == ShellNavChrome.RAIL) ShellRail(selected = selected, onSelect = onSelect)
        val railInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Start)
        Scaffold(
            modifier = Modifier
                .weight(1f)
                .then(if (chrome == ShellNavChrome.RAIL) Modifier.consumeWindowInsets(railInsets) else Modifier),
            bottomBar = {
                if (chrome == ShellNavChrome.BOTTOM_BAR) ShellBottomBar(selected = selected, onSelect = onSelect)
            },
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = if (chrome == ShellNavChrome.BOTTOM_BAR) {
                        Modifier
                    } else {
                        Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    },
                )
            },
            // Screens apply their own system-bar insets; the shell only reserves its bar.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { innerPadding ->
            DftbNavHost(
                startDestination = startDestination,
                navController = navController,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    // Edge-to-edge + adjustResize does not physically shrink the window, so without
                    // this the soft keyboard draws over the content and focused text fields end up
                    // hidden behind it. imePadding() shrinks the content area by the keyboard height;
                    // each screen's scrollable then auto-scrolls the focused field into view.
                    .imePadding(),
            )
        }
    }
}

/** Navigates to the pending deep link once the graph exists and onboarding is behind us. */
@Composable
private fun DeepLinkEffect(viewModel: ShellViewModel, navController: NavHostController, currentRoute: String?) {
    val pending by viewModel.pendingDeepLink.collectAsStateWithLifecycle()
    val ready = currentRoute != null && !ShellRoutes.isOnboarding(currentRoute)
    LaunchedEffect(pending, ready) {
        val link = pending ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        val target = viewModel.resolve(link)
        try {
            navController.navigateToShellTarget(target)
        } catch (e: IllegalArgumentException) {
            // A route missing from the graph must not crash a notification tap.
            DftbLog.w(TAG, "Deep link target $target is not in the graph", e)
        }
        viewModel.consume(link)
    }
}

private const val TAG = "Shell"

/** Section roots switch tabs; anything else is pushed once (a second job link replaces the monitor). */
internal fun NavHostController.navigateToShellTarget(target: DftbDestination) {
    val topLevel = TopLevelDestination.entries.firstOrNull { it.route == target }
    if (topLevel != null) {
        navigateToTopLevel(topLevel)
        return
    }
    navigate(target) {
        if (target is DftbDestination.JobMonitor) popUpTo<DftbDestination.JobMonitor> { inclusive = true }
        launchSingleTop = true
    }
}

/**
 * Starts the system install prompt of the next pending app restore while DFTB is resumed, waits
 * for the user to come back before the next one, and offers prompts that are still unanswered
 * after being shown through a persistent snackbar instead of relaunching them in a loop.
 */
@Composable
private fun InstallPromptEffect(viewModel: ShellViewModel, snackbarHostState: SnackbarHostState) {
    val prompts by viewModel.installPrompts.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val activity = LocalActivity.current
    val context = LocalContext.current
    val launchContext by rememberUpdatedState(activity ?: context)

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.onPaused() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResumed() }

    val next = InstallPromptQueue.nextToLaunch(prompts.pendingIds, prompts.state, resumed)
    LaunchedEffect(next) {
        val sessionId = next ?: return@LaunchedEffect
        val event = prompts.event(sessionId) ?: return@LaunchedEffect
        // The PendingIntent-derived intent is reused across relaunches; never mutate the earlier build.
        if (ExternalLinks.launch(launchContext, android.content.Intent(event.intent))) {
            viewModel.onPromptLaunched(sessionId)
        } else {
            viewModel.onPromptLaunchFailed(sessionId)
        }
    }

    val inFlight = prompts.state.inFlight
    LaunchedEffect(inFlight) {
        val sessionId = inFlight ?: return@LaunchedEffect
        delay(PROMPT_COVER_TIMEOUT_MILLIS)
        viewModel.onPromptTimedOut(sessionId)
    }

    val waiting = InstallPromptQueue.awaiting(prompts.pendingIds, prompts.state).mapNotNull(prompts::event)
    val first = waiting.firstOrNull()
    val message = when {
        first == null -> null
        waiting.size == 1 -> stringResource(R.string.shell_install_waiting_one, first.label)
        else -> pluralStringResource(R.plurals.shell_install_waiting_many, waiting.size - 1, first.label, waiting.size - 1)
    }
    val reviewLabel = stringResource(R.string.shell_install_review)
    LaunchedEffect(first?.sessionId, message) {
        if (first == null || message == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = reviewLabel,
            withDismissAction = true,
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.relaunchPrompt(first.sessionId)
    }
}

/** A prompt that has not covered DFTB within this time is treated as not shown. */
private const val PROMPT_COVER_TIMEOUT_MILLIS = 5_000L
