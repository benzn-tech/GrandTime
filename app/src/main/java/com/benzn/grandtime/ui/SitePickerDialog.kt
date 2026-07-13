package com.benzn.grandtime.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.benzn.grandtime.BuildConfig
import com.benzn.grandtime.GrandTimeApp
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.core.SelectedSite
import com.benzn.grandtime.core.SiteStore
import com.benzn.grandtime.core.siteDataStore
import com.benzn.grandtime.net.SitesApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface SitePickerState {
    data object Loading : SitePickerState
    data class Loaded(val sites: List<SitesApiClient.SiteOption>) : SitePickerState
    data object Failed : SitePickerState
}

/** 工地选择弹窗:拉 GET /org/sites,选中后写 SiteStore(经 CoreService 镜像回 AppState)。 */
@Composable
fun SitePickerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cachedSites = AppState.availableSites.value
    var state by remember {
        mutableStateOf<SitePickerState>(
            if (cachedSites.isNotEmpty()) SitePickerState.Loaded(cachedSites) else SitePickerState.Loading,
        )
    }
    var currentSiteId by remember { mutableStateOf<String?>(null) }

    // 缓存(CoreService 启动时预取)命中则秒开;这里的拉取只是后台刷新——
    // 成功则更新缓存+本地态,失败时若已有缓存在展示则不覆盖(仅 Loading 起点才降级 Failed)。
    LaunchedEffect(Unit) {
        currentSiteId = SiteStore(context.siteDataStore).site.first()?.id
        val result = withContext(Dispatchers.IO) {
            val idToken = (context.applicationContext as GrandTimeApp).authManager.freshIdToken()
            if (idToken == null) {
                null
            } else {
                SitesApiClient(BuildConfig.ORG_API_BASE_URL).listSites(idToken)
            }
        }
        if (result != null) {
            AppState.availableSites.value = result
            state = SitePickerState.Loaded(result)
        } else if (state is SitePickerState.Loading) {
            state = SitePickerState.Failed
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.95f),
        title = { Text("Select site", style = MaterialTheme.typography.headlineSmall) },
        text = {
            when (val s = state) {
                SitePickerState.Loading -> {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text(
                                "Loading sites…",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                }
                SitePickerState.Failed -> {
                    Text(
                        "Could not load sites. Check your connection and try again.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                is SitePickerState.Loaded -> {
                    if (s.sites.isEmpty()) {
                        Text(
                            "No sites available for your account yet.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    } else {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            s.sites.forEach { site ->
                                val selected = site.id == currentSiteId
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 56.dp)
                                        .clickable {
                                            scope.launch {
                                                SiteStore(context.siteDataStore).set(
                                                    SelectedSite(site.id, site.slug, site.name),
                                                )
                                            }
                                            onDismiss()
                                        }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                ) {
                                    Text(
                                        site.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (selected) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
