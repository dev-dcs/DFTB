package ca.devdcs.backup.ui.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.devdcs.backup.R
import ca.devdcs.backup.ui.components.SectionCard
import ca.devdcs.backup.ui.destinations.FormTextField
import kotlinx.coroutines.launch

/** Content padding of every wizard page body. */
internal val PagePadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)

/**
 * Create / edit a backup profile in six steps (name, targets, destination, options, schedule &
 * retention, review). Back/Next validate per step; the step list (a rail on wide windows, chips on
 * phones) jumps to any page already reachable. The draft survives process death (SavedStateHandle).
 */
@Composable
fun ProfileWizardScreen(
    onExit: () -> Unit,
    onSaved: () -> Unit,
    onSavedAndRunning: (jobId: String?) -> Unit,
    onAddDestination: () -> Unit,
    viewModel: ProfileWizardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                WizardEvent.Saved -> onSaved()
                is WizardEvent.SavedAndRunning -> onSavedAndRunning(event.jobId)
                WizardEvent.Exit -> onExit()
                is WizardEvent.Message -> {
                    // Own coroutine: awaiting the snackbar must not hold up a following Saved/Exit.
                    launch { snackbarHostState.showSnackbar(context.getString(event.messageRes)) }
                }
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshEnvironment() }
    BackHandler { if (!viewModel.back()) viewModel.requestExit() }

    val draft = state.draft
    val widthClass = rememberWidthClass()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(if (draft?.isNew != false) R.string.profiles_wizard_new_title else R.string.profiles_wizard_edit_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = viewModel::requestExit) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.profiles_wizard_close))
                    }
                },
            )
        },
        bottomBar = {
            if (draft != null) {
                WizardBottomBar(
                    step = draft.step,
                    saving = state.saving,
                    onBack = { if (!viewModel.back()) viewModel.requestExit() },
                    onNext = viewModel::next,
                    onSave = { viewModel.save(runNow = false) },
                    onSaveAndRun = { viewModel.save(runNow = true) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val body = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        when {
            state.loading -> Box(body, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            draft == null -> Box(body.padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.profiles_wizard_not_found), style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = onExit) { Text(stringResource(R.string.profiles_wizard_leave)) }
                }
            }
            widthClass == WidthClass.EXPANDED -> Row(body) {
                StepRail(state, onSelect = viewModel::goTo, modifier = Modifier.width(RAIL_WIDTH).fillMaxHeight())
                VerticalDivider()
                StepBody(state, viewModel, onAddDestination, Modifier.weight(1f).fillMaxHeight())
            }
            else -> Column(body) {
                StepHeader(state, onSelect = viewModel::goTo)
                StepBody(state, viewModel, onAddDestination, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }

    if (state.confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.profiles_wizard_discard_title),
            consequence = stringResource(
                if (draft?.isNew != false) R.string.profiles_wizard_discard_new else R.string.profiles_wizard_discard_edit,
            ),
            confirmLabel = stringResource(R.string.profiles_wizard_discard_confirm),
            onConfirm = viewModel::confirmDiscard,
            onDismiss = viewModel::cancelDiscard,
        )
    }
    state.passphrasePrompt?.let { profile ->
        PassphraseDialog(profileName = profile.name, onSubmit = viewModel::submitPassphrase, onDismiss = viewModel::dismissPassphrase)
    }
    state.filterEditor?.let { editor ->
        FilterEditorDialog(
            editor = editor,
            onChange = viewModel::updateFilterForm,
            onReset = viewModel::resetFilterForm,
            onSave = { viewModel.saveFilter() },
            onDismiss = viewModel::dismissFilterEditor,
        )
    }
}

private val RAIL_WIDTH = 260.dp

@Composable
private fun StepBody(state: WizardUiState, viewModel: ProfileWizardViewModel, onAddDestination: () -> Unit, modifier: Modifier) {
    when (state.step) {
        WizardStep.NAME -> NameStep(state, viewModel, modifier)
        WizardStep.TARGETS -> TargetsStep(state, viewModel, modifier)
        WizardStep.DESTINATION -> DestinationStep(
            state = state,
            viewModel = viewModel,
            onAddDestination = {
                viewModel.onAddDestination()
                onAddDestination()
            },
            modifier = modifier,
        )
        WizardStep.OPTIONS -> OptionsStep(state, viewModel, modifier)
        WizardStep.SCHEDULE -> ScheduleStep(state, viewModel, modifier)
        WizardStep.REVIEW -> ReviewStep(state, viewModel, modifier)
    }
}

/** Status icon of a step in the step list: done, has errors, or not yet done. */
@Composable
private fun StepStatusIcon(state: WizardUiState, step: WizardStep) {
    val draft = state.draft ?: return
    val hasErrors = DraftValidator.hasErrors(state.problems, step)
    when {
        hasErrors && step in draft.showErrorsFor -> Icon(
            Icons.Outlined.ErrorOutline,
            contentDescription = stringResource(R.string.profiles_cd_step_errors),
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        !hasErrors && step in draft.visited -> Icon(
            Icons.Outlined.CheckCircle,
            contentDescription = stringResource(R.string.profiles_cd_step_done),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        else -> Icon(
            Icons.Outlined.RadioButtonUnchecked,
            contentDescription = stringResource(R.string.profiles_cd_step_todo),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun StepRail(state: WizardUiState, onSelect: (WizardStep) -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        WizardStep.entries.forEach { step ->
            NavigationDrawerItem(
                label = {
                    Column {
                        Text(
                            stringResource(R.string.profiles_step_numbered, step.ordinal + 1, stringResource(step.titleRes())),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            stringResource(step.subtitleRes()),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                selected = step == state.step,
                onClick = { onSelect(step) },
                icon = { StepStatusIcon(state, step) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StepHeader(state: WizardUiState, onSelect: (WizardStep) -> Unit) {
    val step = state.step
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(
            progress = { (step.ordinal + 1).toFloat() / WizardStep.entries.size },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clearAndSetSemantics { },
        )
        Text(
            stringResource(R.string.profiles_step_of, step.ordinal + 1, WizardStep.entries.size, stringResource(step.titleRes())),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WizardStep.entries.forEach { entry ->
                FilterChip(
                    selected = entry == step,
                    onClick = { onSelect(entry) },
                    enabled = state.canOpen(entry),
                    leadingIcon = { StepStatusIcon(state, entry) },
                    label = { Text(stringResource(entry.titleRes())) },
                )
            }
        }
    }
}

@Composable
private fun WizardBottomBar(
    step: WizardStep,
    saving: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSave: () -> Unit,
    onSaveAndRun: () -> Unit,
) {
    BottomAppBar {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onBack, enabled = !saving) {
                if (step != WizardStep.NAME) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Text(
                    stringResource(if (step == WizardStep.NAME) R.string.profiles_cancel else R.string.profiles_wizard_back),
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (saving) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            if (step == WizardStep.REVIEW) {
                OutlinedButton(onClick = onSave, enabled = !saving) { Text(stringResource(R.string.profiles_wizard_save)) }
                Button(onClick = onSaveAndRun, enabled = !saving) { Text(stringResource(R.string.profiles_wizard_save_run)) }
            } else {
                Button(onClick = onNext, enabled = !saving) {
                    Text(stringResource(R.string.profiles_wizard_next))
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 4.dp).size(18.dp),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ (a) name

@Composable
private fun NameStep(state: WizardUiState, viewModel: ProfileWizardViewModel, modifier: Modifier) {
    val draft = state.draft ?: return
    val problems = state.visibleProblemsFor(WizardStep.NAME)
    fun errorOf(vararg kinds: DraftProblem): DraftProblem? = problems.firstOrNull { it in kinds }
    LazyColumn(modifier = modifier, contentPadding = PagePadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "name") {
            SectionCard(title = stringResource(R.string.profiles_step_name), subtitle = stringResource(R.string.profiles_step_name_sub)) {
                val nameError = errorOf(DraftProblem.NAME_BLANK, DraftProblem.NAME_TOO_LONG, DraftProblem.NAME_DUPLICATE)
                FormTextField(
                    value = draft.name,
                    onValueChange = { value -> viewModel.edit { it.copy(name = value) } },
                    label = stringResource(R.string.profiles_field_name),
                    placeholder = stringResource(R.string.profiles_field_name_placeholder),
                    error = nameError?.let { stringResource(it.messageRes()) },
                    hint = stringResource(R.string.profiles_field_name_hint, draft.name.trim().length, DraftValidator.MAX_NAME_LENGTH),
                )
                val descriptionError = errorOf(DraftProblem.DESCRIPTION_TOO_LONG)
                FormTextField(
                    value = draft.description,
                    onValueChange = { value -> viewModel.edit { it.copy(description = value) } },
                    label = stringResource(R.string.profiles_field_description),
                    error = descriptionError?.let { stringResource(it.messageRes()) },
                    singleLine = false,
                    minLines = 2,
                )
            }
        }
    }
}
