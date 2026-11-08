package ca.devdcs.backup.ui.restore

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.devdcs.backup.R
import ca.devdcs.backup.core.restore.engine.RestoreUnitKind
import ca.devdcs.backup.ui.components.SectionCard
import kotlinx.coroutines.launch

/**
 * One backup on a destination: unlock, details, units, verification, and the restore wizard
 * (select → options → preflight → run). Phones show one column (the wizard replaces the overview
 * and Back walks the steps); wide windows show the overview beside the wizard / run panel.
 */
@Composable
fun RestoreBackupScreen(
    onBack: () -> Unit,
    viewModel: RestoreBackupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val twoPane = useTwoPanes()
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.onTreePicked(uri)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                RestoreBackupEvent.TreeGrantFailed -> context.getString(R.string.restore_ui_tree_grant_failed)
                RestoreBackupEvent.RestoreStarted -> context.getString(R.string.restore_ui_run_started)
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    val openIntent: (Intent) -> Unit = { intent ->
        try {
            if (activity != null) activity.startActivity(intent) else scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.restore_ui_intent_failed)) }
        } catch (e: ActivityNotFoundException) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.restore_ui_intent_failed)) }
        } catch (e: SecurityException) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.restore_ui_intent_failed)) }
        }
    }

    val inWizard = state.input.step != RestoreStep.DETAILS && state.lock == LockState.OPEN && !state.runActive
    BackHandler(enabled = inWizard) { viewModel.back() }

    val wizard = WizardCallbacks(
        toggleUnit = viewModel::toggleUnit,
        toggleKind = viewModel::toggleKind,
        selectAll = viewModel::selectAll,
        options = OptionsActions(
            edit = viewModel::editInput,
            setFileTarget = viewModel::setFileTarget,
            setRisky = viewModel::setRisky,
            pickTree = { treeLauncher.launch(null) },
            back = viewModel::back,
            next = viewModel::next,
        ),
        back = viewModel::back,
        next = viewModel::next,
        retryPreflight = viewModel::runPreflight,
        start = viewModel::startRestore,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.details?.manifest?.profile?.name ?: state.jobDir.trimEnd('/').substringAfterLast('/'),
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (inWizard && !twoPane) viewModel.back() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.restore_ui_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (twoPane) {
            Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Overview(state, viewModel, showStart = false)
                }
                VerticalDivider()
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val run = state.run
                    when {
                        run != null -> RestoreRunPanel(run, onCancel = viewModel::cancelRestore, onDismiss = viewModel::dismissRun, onOpenIntent = openIntent)
                        inWizard -> WizardContent(state, wizard)
                        else -> StartCard(state, onStart = viewModel::startWizard)
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (inWizard) {
                    WizardContent(state, wizard)
                } else {
                    state.run?.let { run ->
                        RestoreRunPanel(run, onCancel = viewModel::cancelRestore, onDismiss = viewModel::dismissRun, onOpenIntent = openIntent)
                    }
                    Overview(state, viewModel, showStart = true)
                }
            }
        }
    }

    if (state.showPassphraseDialog) {
        PassphraseDialog(
            busy = state.lock == LockState.UNLOCKING,
            error = state.passphraseError?.text(),
            note = state.vaultProblem?.let { stringResource(R.string.restore_ui_locked_vault_problem, it.text()) },
            onSubmit = viewModel::unlock,
            onDismiss = viewModel::dismissPassphrase,
        )
    }
}

/** The wizard's callbacks, bundled to keep the step composables' signatures short. */
class WizardCallbacks(
    val toggleUnit: (String) -> Unit,
    val toggleKind: (RestoreUnitKind) -> Unit,
    val selectAll: (Boolean) -> Unit,
    val options: OptionsActions,
    val back: () -> Unit,
    val next: () -> Unit,
    val retryPreflight: () -> Unit,
    val start: () -> Unit,
)

@Composable
private fun WizardContent(state: RestoreBackupUiState, callbacks: WizardCallbacks) {
    when (state.input.step) {
        RestoreStep.SELECT -> SelectStep(
            state = state,
            onToggleUnit = callbacks.toggleUnit,
            onToggleKind = callbacks.toggleKind,
            onSelectAll = callbacks.selectAll,
            onBack = callbacks.back,
            onNext = callbacks.next,
        )
        RestoreStep.OPTIONS -> OptionsStep(state, callbacks.options)
        RestoreStep.PREFLIGHT -> PreflightStep(state, onRetry = callbacks.retryPreflight, onBack = callbacks.back, onStart = callbacks.start)
        RestoreStep.DETAILS -> Unit
    }
}

@Composable
private fun Overview(state: RestoreBackupUiState, viewModel: RestoreBackupViewModel, showStart: Boolean) {
    LockSection(state, onEnterPassphrase = viewModel::showPassphrase, onRetry = viewModel::retryOpen)
    val details = state.details ?: return
    if (showStart) StartCard(state, onStart = viewModel::startWizard)
    DetailsSection(details, state.thisDevice)
    UnitsSection(state.groups)
    VerifySection(
        verify = state.verify,
        enabled = state.lock == LockState.OPEN,
        onVerify = viewModel::verify,
        onCancel = viewModel::cancelVerify,
    )
}

@Composable
private fun StartCard(state: RestoreBackupUiState, onStart: () -> Unit) {
    if (state.lock != LockState.OPEN) return
    SectionCard(title = stringResource(R.string.restore_ui_start_title), icon = Icons.Outlined.Restore, subtitle = stringResource(R.string.restore_ui_start_subtitle)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.runActive) {
                Text(stringResource(R.string.restore_ui_start_busy), style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = onStart, enabled = !state.runActive && state.units.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.restore_ui_start_wizard))
            }
        }
    }
}
