@file:OptIn(ExperimentalMaterial3Api::class)

package app.cockpitmanager.ui.services

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cockpitmanager.system.ServiceAction
import app.cockpitmanager.system.ServiceUnit
import app.cockpitmanager.ui.components.CockpitIcons
import app.cockpitmanager.ui.components.EmptyState
import app.cockpitmanager.ui.components.ErrorCard
import app.cockpitmanager.ui.components.StatusDot
import app.cockpitmanager.ui.theme.AppTheme
import app.cockpitmanager.ui.theme.StatusColors

fun ServiceUnit.statusColor(status: StatusColors): Color = when {
    isFailed -> status.bad
    isTransitioning -> status.warn
    isActive -> status.good
    else -> status.idle
}

/** Rows in one rounded group, as in Material 3 lists: big corners at the ends, small between. */
fun groupShape(index: Int, count: Int): RoundedCornerShape {
    val big = 20.dp
    val small = 4.dp
    return RoundedCornerShape(
        topStart = if (index == 0) big else small,
        topEnd = if (index == 0) big else small,
        bottomStart = if (index == count - 1) big else small,
        bottomEnd = if (index == count - 1) big else small,
    )
}

@Composable
fun ServicesScreen(
    state: ServicesState,
    onRefresh: () -> Unit,
    onFilter: (ServiceFilter) -> Unit,
    onQuery: (String) -> Unit,
    onOpen: (ServiceUnit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = state.visible
    Column(modifier.fillMaxSize()) {
        SearchField(state.query, onQuery, "Search services", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ServiceFilter.entries.forEach { f ->
                val count = state.count(f)
                FilterChip(
                    selected = state.filter == f && state.query.isBlank(),
                    onClick = { onFilter(f) },
                    label = { Text(if (state.services.isEmpty()) f.label else "${f.label} $count") },
                )
            }
        }
        PullToRefreshBox(isRefreshing = state.loading && state.services.isNotEmpty(), onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                state.error?.let { msg -> item { ErrorCard(msg, actionLabel = "Retry", onAction = onRefresh, modifier = Modifier.padding(bottom = 10.dp)) } }
                if (state.loading && state.services.isEmpty()) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                    }
                } else if (visible.isEmpty() && state.error == null) {
                    item {
                        EmptyState(
                            CockpitIcons.Services,
                            if (state.query.isNotBlank()) "No matches" else "Nothing here",
                            when {
                                state.query.isNotBlank() -> "No service matches \"${state.query}\"."
                                state.filter == ServiceFilter.FAILED -> "No services have failed."
                                else -> null
                            },
                        )
                    }
                }
                itemsIndexed(visible, key = { _, s -> s.name }) { i, service ->
                    ServiceRow(service, state.busy[service.name], groupShape(i, visible.size), onClick = { onOpen(service) })
                }
            }
        }
    }
}

@Composable
private fun ServiceRow(service: ServiceUnit, busy: ServiceAction?, shape: RoundedCornerShape, onClick: () -> Unit) {
    val status = AppTheme.status
    Row(
        Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(service.statusColor(status))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(service.shortName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (service.description.isNotBlank() && service.description != service.name) {
                Text(service.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        if (busy != null) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (service.isActive) service.subState.ifBlank { service.activeState } else service.activeState,
                    style = MaterialTheme.typography.labelMedium,
                    color = service.statusColor(status),
                )
                service.fileState?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = if (value.isNotEmpty()) { { IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear") } } } else null,
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedBorderColor = Color.Transparent,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}
