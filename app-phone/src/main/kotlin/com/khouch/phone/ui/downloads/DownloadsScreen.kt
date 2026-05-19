package com.khouch.phone.ui.downloads

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import coil3.compose.AsyncImage
import com.khouch.core.data.downloads.DownloadEntry
import com.khouch.core.data.downloads.DownloadsRepo
import org.koin.androidx.compose.koinViewModel

class DownloadsViewModel(private val repo: DownloadsRepo) : ViewModel() {
    val items = repo.items
    val totalBytes = repo.totalBytes
    fun delete(e: DownloadEntry) = repo.delete(e.mode, e.id)
    fun deleteAll() = repo.deleteAll()
    fun deleteMany(keys: Set<String>) {
        // keys are "${mode}_${id}" — same shape as the LazyColumn item key.
        val snapshot = items.value
        for (e in snapshot) {
            if ("${e.mode}_${e.id}" in keys) repo.delete(e.mode, e.id)
        }
    }
}

private fun keyOf(e: DownloadEntry) = "${e.mode}_${e.id}"

@Composable
fun DownloadsScreen(
    onPlay: (DownloadEntry) -> Unit,
    onBack: () -> Unit,
) {
    val vm: DownloadsViewModel = koinViewModel()
    val items by vm.items.collectAsState()
    val total by vm.totalBytes.collectAsState()
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }

    // Selection mode: a non-empty set of "${mode}_${id}" keys enters
    // multi-select. Long-press a row to start; Cancel or system back
    // clears it. The selection survives the live items StateFlow
    // re-emitting (e.g. download progress) because we key by mode+id,
    // not list index.
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val inSelectionMode = selected.isNotEmpty()

    // Drop any keys whose items vanish (e.g. were deleted from the
    // bulk-delete itself, or completed → re-indexed elsewhere). Keeps
    // the "N selected" header honest.
    LaunchedEffect(items) {
        val live = items.mapTo(HashSet()) { keyOf(it) }
        if (selected.any { it !in live }) {
            selected = selected.filter { it in live }.toSet()
        }
    }

    // System back exits selection mode first, only navigates out
    // when nothing is selected.
    BackHandler {
        if (inSelectionMode) selected = emptySet() else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (inSelectionMode) "${selected.size} selected" else "Downloads",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        if (!inSelectionMode) {
                            Text(
                                "${items.size} items · ${formatBytes(total)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (inSelectionMode) {
                        IconButton(onClick = { selected = emptySet() }) {
                            Icon(Icons.Default.Close, "Exit selection")
                        }
                    }
                },
                actions = {
                    if (inSelectionMode) {
                        // Bulk actions: select-all toggle + delete.
                        val allSelected = selected.size == items.size
                        TextButton(onClick = {
                            selected = if (allSelected) emptySet()
                            else items.mapTo(HashSet()) { keyOf(it) }
                        }) {
                            Text(if (allSelected) "None" else "All")
                        }
                        TextButton(onClick = { confirmDeleteSelected = true }) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    } else if (items.isNotEmpty()) {
                        TextButton(onClick = { confirmDeleteAll = true }) {
                            Text("Delete all", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pv ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pv), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.DownloadDone,
                        null,
                        Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "No downloads yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Tap the download icon on a movie or episode",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pv)) {
                items(items, key = { keyOf(it) }) { e ->
                    val key = keyOf(e)
                    val isSelected = key in selected
                    DownloadRow(
                        e = e,
                        inSelectionMode = inSelectionMode,
                        isSelected = isSelected,
                        onTap = {
                            if (inSelectionMode) {
                                selected = if (isSelected) selected - key else selected + key
                            } else if (e.status == DownloadEntry.Status.COMPLETED) {
                                onPlay(e)
                            }
                        },
                        onLongPress = {
                            // Long-press toggles inclusion. If nothing
                            // is selected, this starts selection mode.
                            selected = if (isSelected) selected - key else selected + key
                        },
                        onDelete = { vm.delete(e) },
                    )
                    HorizontalDivider(color = Color(0xFF1F2440), thickness = 0.5.dp)
                }
            }
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all downloads?") },
            text = {
                Text("This will remove ${items.size} items (${formatBytes(total)}). " +
                    "Files will be deleted from the device — server state is unaffected.")
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.deleteAll(); confirmDeleteAll = false },
                ) { Text("Delete all", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmDeleteSelected) {
        val selectedBytes = items
            .filter { keyOf(it) in selected }
            .sumOf { it.sizeBytes.coerceAtLeast(0L) }
        AlertDialog(
            onDismissRequest = { confirmDeleteSelected = false },
            title = { Text("Delete ${selected.size} item${if (selected.size == 1) "" else "s"}?") },
            text = {
                Text("Files will be removed from the device " +
                    "(${formatBytes(selectedBytes)}). Server state is unaffected.")
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteMany(selected)
                    selected = emptySet()
                    confirmDeleteSelected = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSelected = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadRow(
    e: DownloadEntry,
    inSelectionMode: Boolean,
    isSelected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDelete: () -> Unit,
) {
    // Tinted background when the row is selected so the state is
    // visible even when the user has scrolled the leading checkbox
    // off the screen.
    val rowBg = if (isSelected)
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    else Color.Transparent

    Row(
        Modifier
            .fillMaxWidth()
            .background(rowBg)
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading checkbox replaces the poster while in selection mode
        // so the screen doesn't shift sideways when entering it.
        if (inSelectionMode) {
            Icon(
                if (isSelected) Icons.Outlined.CheckCircle
                else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = if (isSelected) "Selected" else "Not selected",
                modifier = Modifier.size(28.dp),
                tint = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
        }
        // Poster thumb
        Box(
            Modifier
                .width(54.dp)
                .height(72.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (!e.poster.isNullOrBlank()) {
                AsyncImage(
                    model = e.poster,
                    contentDescription = e.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    e.name.take(2).uppercase(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.name,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusIcon(e.status)
                Spacer(Modifier.width(4.dp))
                Text(
                    statusLabel(e),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                if (e.seriesId != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "S${e.seasonNum ?: "?"}·E${e.episodeNum ?: "?"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp,
                    )
                }
            }
        }
        // Trailing per-row delete hides during selection mode — the
        // bulk Delete in the top bar covers it without a second tap
        // target that's easy to hit accidentally.
        if (!inSelectionMode) {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.DeleteForever, "Delete",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun StatusIcon(s: DownloadEntry.Status) {
    val (icon, tint) = when (s) {
        DownloadEntry.Status.COMPLETED -> Icons.Default.DownloadDone to MaterialTheme.colorScheme.primary
        DownloadEntry.Status.RUNNING   -> Icons.Default.Downloading to MaterialTheme.colorScheme.primary
        DownloadEntry.Status.PENDING   -> Icons.Default.Downloading to MaterialTheme.colorScheme.onSurfaceVariant
        DownloadEntry.Status.FAILED    -> Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
    }
    Icon(icon, null, Modifier.size(12.dp), tint = tint)
}

private fun statusLabel(e: DownloadEntry): String = when (e.status) {
    DownloadEntry.Status.COMPLETED -> formatBytes(e.sizeBytes)
    DownloadEntry.Status.RUNNING   -> "Downloading… ${formatBytes(e.sizeBytes)}"
    DownloadEntry.Status.PENDING   -> "Queued"
    DownloadEntry.Status.FAILED    -> "Failed — tap delete to remove"
}

fun formatBytes(b: Long): String {
    if (b <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digits = (Math.log10(b.toDouble()) / Math.log10(1024.0)).toInt().coerceAtMost(units.size - 1)
    val val_ = b / Math.pow(1024.0, digits.toDouble())
    return if (digits == 0) "${b} B"
    else "%.1f %s".format(val_, units[digits])
}
