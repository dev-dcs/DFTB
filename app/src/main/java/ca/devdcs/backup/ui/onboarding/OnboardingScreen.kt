package ca.devdcs.backup.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.devdcs.backup.R
import kotlinx.coroutines.launch

/**
 * First-launch wizard. Six pages slide horizontally (direction-aware, so RTL users see them come
 * from the correct side); the ViewModel owns which page is shown so the system Back gesture, the
 * bottom bar and a rotation all agree. [onFinished] fires once the preferences are persisted.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context by rememberUpdatedState(LocalContext.current)
    val finished by rememberUpdatedState(onFinished)
    val snackbarScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                OnboardingEvent.Finished -> finished()
                // Show the snackbar on a separate scope so awaiting its (full display duration)
                // dismissal never stalls the collector and the events queued behind it, such as Finished.
                is OnboardingEvent.ShowMessage ->
                    snackbarScope.launch { snackbarHostState.showSnackbar(context.getString(event.messageRes)) }
            }
        }
    }

    // Decoupled from canGoBack (which drops to false while finishing): staying enabled on the last
    // step during the commit consumes system Back instead of letting it exit the start-destination
    // Activity. viewModel.back() is a no-op when canGoBack is false, so it never steps back mid-commit.
    BackHandler(enabled = !state.step.isFirst) { viewModel.back() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResumed() }

    Scaffold(
        topBar = { OnboardingTopBar(step = state.step) },
        bottomBar = {
            OnboardingBottomBar(
                state = state,
                onBack = viewModel::back,
                onNext = viewModel::next,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        AnimatedContent(
            targetState = state.step,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            transitionSpec = {
                val forward = targetState.ordinal >= initialState.ordinal
                val direction = if (forward) {
                    AnimatedContentTransitionScope.SlideDirection.Start
                } else {
                    AnimatedContentTransitionScope.SlideDirection.End
                }
                (slideIntoContainer(direction) + fadeIn()) togetherWith (slideOutOfContainer(direction) + fadeOut())
            },
            label = "onboarding-step",
        ) { step ->
            when (step) {
                OnboardingStep.LANGUAGE -> LanguageStep(state = state, onSelect = viewModel::setLanguage)
                OnboardingStep.OVERVIEW -> OverviewStep()
                OnboardingStep.DISCLAIMER -> DisclaimerStep(
                    accepted = state.disclaimerAccepted,
                    onAcceptedChange = viewModel::setDisclaimerAccepted,
                )
                OnboardingStep.LICENSES -> LicensesStep(onMessage = viewModel::showMessage)
                OnboardingStep.MODE -> ModeStep(state = state, viewModel = viewModel)
                OnboardingStep.BASELINE -> BaselineStep(state = state, viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun OnboardingTopBar(step: OnboardingStep) {
    Column {
        TopAppBar(title = { Text(stringResource(R.string.onboarding_title)) })
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            LinearProgressIndicator(
                progress = { (step.ordinal + 1).toFloat() / OnboardingStep.COUNT },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.onboarding_step_counter, step.ordinal + 1, OnboardingStep.COUNT),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun OnboardingBottomBar(state: OnboardingUiState, onBack: () -> Unit, onNext: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            if (state.finishing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(R.string.onboarding_finishing),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onBack, enabled = state.canGoBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.onboarding_back))
                }
                Button(onClick = onNext, enabled = state.canGoNext) {
                    Text(stringResource(if (state.step.isLast) R.string.onboarding_finish else R.string.onboarding_next))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Icon(
                        imageVector = if (state.step.isLast) Icons.Default.Check else Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                }
            }
        }
    }
}
