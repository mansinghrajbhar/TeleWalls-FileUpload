package me.jaival.telewalls.ui.screens.collections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import me.jaival.telewalls.data.repository.Wallpaper
import me.jaival.telewalls.ui.components.AnimatedWallpaperCard
import me.jaival.telewalls.ui.components.BatchSelectionHeader
import me.jaival.telewalls.ui.dialogs.BatchEditDialog
import me.jaival.telewalls.viewmodel.CategoryDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryDetailScreen(
    viewModel: CategoryDetailViewModel,
    onWallpaperClick: (String) -> Unit,
    onBackClick: () -> Unit,
    showBackButton: Boolean = true,
    scrollToTopTrigger: Int = 0
) {
    val context = LocalContext.current
    val wallpapers by viewModel.wallpapers.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val gridState = rememberLazyGridState()
    var isInitialTabOpen by remember { mutableStateOf(true) }
    var selectedExtension by remember { mutableStateOf("ALL") }

    fun extensionOf(file: Wallpaper): String {
        return file.fileName.substringAfterLast(".", "").takeIf { it.isNotBlank() }?.uppercase()
            ?: if (file.mimeType.startsWith("image/")) "IMG" else "FILE"
    }

    val extensionCounts = remember(wallpapers) {
        wallpapers.groupingBy { extensionOf(it) }.eachCount().toList().sortedByDescending { it.second }
    }
    val visibleWallpapers = if (selectedExtension == "ALL") wallpapers else wallpapers.filter { extensionOf(it) == selectedExtension }

    var selectedWallpaperIds by remember { mutableStateOf(setOf<String>()) }
    var showBatchEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmationDialog by remember { mutableStateOf(false) }
    val isSelectionMode = selectedWallpaperIds.isNotEmpty()

    BackHandler(enabled = isSelectionMode) {
        selectedWallpaperIds = emptySet()
    }

    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) {
            gridState.animateScrollToItem(0)
        }
    }

    LaunchedEffect(gridState.isScrollInProgress) {
        if (gridState.isScrollInProgress) {
            isInitialTabOpen = false
        }
    }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                BatchSelectionHeader(
                    selectedCount = selectedWallpaperIds.size,
                    totalCount = visibleWallpapers.size,
                    onClearSelection = { selectedWallpaperIds = emptySet() },
                    onSelectAllToggle = {
                        if (selectedWallpaperIds.size == wallpapers.size) {
                            selectedWallpaperIds = emptySet()
                        } else {
                            selectedWallpaperIds = visibleWallpapers.map { it.id }.toSet()
                        }
                    },
                    onEditClick = { showBatchEditDialog = true },
                    onDeleteClick = { showDeleteConfirmationDialog = true },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = viewModel.categoryName,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${wallpapers.size} Files",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    },
                    navigationIcon = {
                        if (showBackButton) {
                            IconButton(onClick = onBackClick) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back"
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (extensionCounts.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val filters = listOf("ALL" to wallpapers.size) + extensionCounts
                    filters.forEach { (extension, count) ->
                        val selected = selectedExtension == extension
                        Text(
                            text = if (extension == "ALL") "All • " + count else extension + " • " + count,
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { selectedExtension = extension }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
            if (wallpapers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "No Files in " + viewModel.categoryName,
                            style = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onBackground)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Add files to this category to see them here.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                }
            } else if (visibleWallpapers.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No " + selectedExtension.lowercase() + " files in this category.")
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(visibleWallpapers, key = { _, item -> item.id }) { index, wallpaper ->
                        val isSelected = wallpaper.id in selectedWallpaperIds
                        AnimatedWallpaperCard(
                            wallpaper = wallpaper,
                            index = index,
                            onClick = {
                                if (isSelectionMode) {
                                    selectedWallpaperIds = if (isSelected) selectedWallpaperIds - wallpaper.id else selectedWallpaperIds + wallpaper.id
                                } else {
                                    onWallpaperClick(wallpaper.id)
                                }
                            },
                            onFavoriteToggle = { viewModel.toggleFavorite(wallpaper.id) },
                            onLoadThumbnail = { viewModel.loadThumbnailOnDemand(it) },
                            animateBounce = isInitialTabOpen,
                            isSelected = isSelected,
                            isSelectionMode = isSelectionMode,
                            onLongClick = {
                                selectedWallpaperIds = if (isSelected) selectedWallpaperIds - wallpaper.id else selectedWallpaperIds + wallpaper.id
                            }
                        )
                    }
                }
            }

            if (showBatchEditDialog) {
                BatchEditDialog(
                    selectedCount = selectedWallpaperIds.size,
                    categories = categories,
                    onDismissRequest = { showBatchEditDialog = false },
                    onSave = { author, wallpaperType, selectedCategory, tags ->
                        showBatchEditDialog = false
                        val selectedWallpapers = visibleWallpapers.filter { it.id in selectedWallpaperIds }
                        val tagsList = tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
                        viewModel.batchUpdateWallpapers(
                            wallpapers = selectedWallpapers,
                            author = author,
                            category = selectedCategory,
                            tags = if (tagsList.isNotEmpty()) tagsList else null,
                            wallpaperType = wallpaperType,
                            onComplete = { count ->
                                val msg = if (count == 1) "Updated 1 wallpaper" else "Updated $count wallpapers"
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                selectedWallpaperIds = emptySet()
                            }
                        )
                    }
                )
            }

            if (showDeleteConfirmationDialog) {
                val count = selectedWallpaperIds.size
                AlertDialog(
                    onDismissRequest = { showDeleteConfirmationDialog = false },
                    title = {
                        Text(
                            text = if (count == 1) "Delete File?" else "Delete $count Files?",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    },
                    text = {
                        Text(
                            text = if (count == 1)
                                "Are you sure you want to delete the selected file? This action cannot be undone."
                            else
                                "Are you sure you want to delete the selected $count files? This action cannot be undone.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showDeleteConfirmationDialog = false
                                val selectedWallpapers = wallpapers.filter { it.id in selectedWallpaperIds }
                                viewModel.deleteWallpapers(
                                    wallpapers = selectedWallpapers,
                                    onComplete = { count ->
                                        val msg = if (count == 1) "Deleted 1 file" else "Deleted $count files"
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        selectedWallpaperIds = emptySet()
                                    }
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("Delete", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showDeleteConfirmationDialog = false }
                        ) {
                            Text("Cancel")
                        }
                    },
                    shape = RoundedCornerShape(24.dp)
                )
            }
        }
    }
}
}