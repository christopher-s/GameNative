package app.gamenative.ui.screen.library.appscreen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.text.HtmlCompat
import app.gamenative.R
import app.gamenative.data.steam.SteamStoreDetails
import app.gamenative.data.steam.SteamStoreDetailsRepository
import app.gamenative.data.steam.SteamStoreDetailsResult
import app.gamenative.ui.component.LoadingScreen
import app.gamenative.ui.util.SnackbarManager
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlinx.coroutines.launch

private sealed interface SteamStoreDetailsUiState {
    data object Loading : SteamStoreDetailsUiState

    data class Ready(
        val details: SteamStoreDetails,
        val isStale: Boolean,
        val isRefreshing: Boolean = false,
    ) : SteamStoreDetailsUiState

    data class Error(
        val message: String,
    ) : SteamStoreDetailsUiState
}

private fun SteamStoreDetailsResult.toUiState(): SteamStoreDetailsUiState = when (this) {
    is SteamStoreDetailsResult.Success -> SteamStoreDetailsUiState.Ready(
        details = details,
        isStale = isStale,
    )
    is SteamStoreDetailsResult.Failure -> SteamStoreDetailsUiState.Error(message)
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun SteamStoreDetailsPanel(
    appId: Int,
    modifier: Modifier = Modifier,
    repository: SteamStoreDetailsRepository = SteamStoreDetailsRepository.shared,
) {
    val context = LocalContext.current
    var refreshRequest by remember(appId) { mutableIntStateOf(0) }
    var state by remember(appId) { mutableStateOf<SteamStoreDetailsUiState>(SteamStoreDetailsUiState.Loading) }

    LaunchedEffect(appId, refreshRequest) {
        val previous = (state as? SteamStoreDetailsUiState.Ready)?.details
        state = if (previous != null) {
            SteamStoreDetailsUiState.Ready(
                details = previous,
                isStale = (state as SteamStoreDetailsUiState.Ready).isStale,
                isRefreshing = true,
            )
        } else {
            SteamStoreDetailsUiState.Loading
        }

        val initialResult = repository.load(
            cacheDir = context.cacheDir,
            appId = appId,
            forceRefresh = refreshRequest > 0,
        )
        state = initialResult.toUiState()

        if (refreshRequest == 0 && initialResult is SteamStoreDetailsResult.Success && initialResult.isStale) {
            state = SteamStoreDetailsUiState.Ready(
                details = initialResult.details,
                isStale = true,
                isRefreshing = true,
            )
            state = repository.load(
                cacheDir = context.cacheDir,
                appId = appId,
                forceRefresh = true,
            ).toUiState()
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SteamStoreHeader(
                appId = appId,
                isRefreshing = (state as? SteamStoreDetailsUiState.Ready)?.isRefreshing == true,
                onRefresh = { refreshRequest++ },
            )

            when (val current = state) {
                SteamStoreDetailsUiState.Loading -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.size(12.dp))
                        Text(
                            text = stringResource(R.string.steam_store_loading),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                is SteamStoreDetailsUiState.Error -> {
                    Text(
                        text = stringResource(R.string.steam_store_load_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = current.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Button(onClick = { refreshRequest++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(stringResource(R.string.steam_store_retry))
                    }
                }
                is SteamStoreDetailsUiState.Ready -> {
                    SteamStoreDetailsContent(
                        details = current.details,
                        isStale = current.isStale,
                        onRefresh = { refreshRequest++ },
                    )
                }
            }
        }
    }
}

@Composable
private fun SteamStoreHeader(
    appId: Int,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.steam_store_details),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onRefresh,
                enabled = !isRefreshing,
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.steam_store_refresh),
                    )
                }
            }
            OutlinedButton(
                onClick = {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://store.steampowered.com/app/$appId"),
                    )
                    runCatching { context.startActivity(intent) }
                        .onFailure { SnackbarManager.show(context.getString(R.string.steam_store_open_failed)) }
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Spacer(modifier = Modifier.size(6.dp))
                Text(stringResource(R.string.steam_store_open))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun SteamStoreDetailsContent(
    details: SteamStoreDetails,
    isStale: Boolean,
    onRefresh: () -> Unit,
) {
    if (isStale) {
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shape = RoundedCornerShape(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.steam_store_cached_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.steam_store_refresh),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }
    }

    val shortDescription = remember(details.shortDescriptionHtml) {
        htmlToPlainText(details.shortDescriptionHtml)
    }
    if (shortDescription.isNotBlank()) {
        Text(
            text = shortDescription,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }

    val metadata = buildList {
        if (details.comingSoon) {
            add(stringResource(R.string.steam_store_coming_soon))
        }
        details.releaseDate.takeIf(String::isNotBlank)?.let(::add)
        details.metacriticScore?.let { add(stringResource(R.string.steam_store_metacritic_score, it)) }
    }
    if (metadata.isNotEmpty() || details.genres.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            metadata.forEach { label -> MetadataChip(label) }
            details.genres.take(8).forEach { genre ->
                MetadataChip(genre)
            }
        }
    }

    if (details.publishers.isNotEmpty()) {
        MetadataLine(
            label = stringResource(R.string.steam_store_publishers),
            value = details.publishers.joinToString(),
        )
    }

    val about = remember(details.aboutTheGameHtml) { htmlToPlainText(details.aboutTheGameHtml) }
    if (about.isNotBlank() && about != shortDescription) {
        ExpandableDescription(
            title = stringResource(R.string.steam_store_about),
            description = about,
        )
    }

    if (details.screenshots.isNotEmpty()) {
        SteamScreenshotPager(
            gameName = details.name,
            screenshots = details.screenshots.map { it.fullUrl },
        )
    }
}

@Composable
private fun MetadataChip(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun MetadataLine(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExpandableDescription(
    title: String,
    description: String,
) {
    var expanded by remember(description) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 8,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(
                if (expanded) R.string.steam_store_show_less else R.string.steam_store_show_more,
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SteamScreenshotPager(
    gameName: String,
    screenshots: List<String>,
) {
    val pagerState = rememberPagerState(pageCount = { screenshots.size })
    val scope = rememberCoroutineScope()
    var fullScreenImage by remember(gameName) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.steam_store_screenshots),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(
                    R.string.steam_store_screenshot_counter,
                    pagerState.currentPage + 1,
                    screenshots.size,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp)),
        ) { page ->
            val imageUrl = screenshots[page]
            CoilImage(
                imageModel = { imageUrl },
                imageOptions = ImageOptions(
                    contentDescription = stringResource(
                        R.string.steam_store_screenshot_description,
                        gameName,
                        page + 1,
                        screenshots.size,
                    ),
                    contentScale = ContentScale.Crop,
                ),
                loading = { LoadingScreen() },
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { fullScreenImage = imageUrl },
            )
        }

        if (screenshots.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(
                                (pagerState.currentPage - 1).coerceAtLeast(0),
                            )
                        }
                    },
                    enabled = pagerState.currentPage > 0,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.steam_store_previous_screenshot),
                    )
                }
                Spacer(modifier = Modifier.size(16.dp))
                IconButton(
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(
                                (pagerState.currentPage + 1).coerceAtMost(screenshots.lastIndex),
                            )
                        }
                    },
                    enabled = pagerState.currentPage < screenshots.lastIndex,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringResource(R.string.steam_store_next_screenshot),
                    )
                }
            }
        }
    }

    fullScreenImage?.let { imageUrl ->
        Dialog(
            onDismissRequest = { fullScreenImage = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                CoilImage(
                    imageModel = { imageUrl },
                    imageOptions = ImageOptions(
                        contentDescription = gameName,
                        contentScale = ContentScale.Fit,
                    ),
                    loading = { LoadingScreen() },
                    modifier = Modifier.fillMaxSize(),
                )
                IconButton(
                    onClick = { fullScreenImage = null },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp)),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

private fun htmlToPlainText(html: String): String {
    if (html.isBlank()) return ""
    return HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
        .toString()
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
