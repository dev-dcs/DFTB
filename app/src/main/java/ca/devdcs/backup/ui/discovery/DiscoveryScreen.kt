package ca.devdcs.backup.ui.discovery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ca.devdcs.backup.R

/**
 * Discovery hub: one place to see what this device exposes for backup (partitions, installed
 * apps, files) before any profile exists. Read-only; each tab's ViewModel is scoped to this
 * navigation entry so switching tabs keeps scans and form input.
 *
 * @param initialTab ordinal of the [DiscoveryTab] to open first (out-of-range values fall back to the first tab).
 */
@Composable
fun DiscoveryScreen(
    onBack: () -> Unit,
    initialTab: Int = 0,
) {
    var selected by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, DiscoveryTab.entries.lastIndex)) }
    val tab = DiscoveryTab.entries[selected]

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.discovery_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.discovery_back))
                        }
                    },
                )
                PrimaryTabRow(selectedTabIndex = selected) {
                    DiscoveryTab.entries.forEachIndexed { index, entry ->
                        Tab(
                            selected = index == selected,
                            onClick = { selected = index },
                            text = { Text(stringResource(entry.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        val listPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)
        val body = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        when (tab) {
            DiscoveryTab.PARTITIONS -> PartitionsTab(contentPadding = listPadding, modifier = body)
            DiscoveryTab.APPS -> AppsTab(contentPadding = listPadding, modifier = body)
            DiscoveryTab.FILES -> FilesTab(contentPadding = listPadding, modifier = body)
        }
    }
}
