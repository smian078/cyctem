package com.cystem.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cystem.core.model.Message
import com.cystem.core.model.MessageRole
import com.cystem.ui.theme.CystemTheme
import kotlinx.coroutines.launch

@Composable
fun CystemApp(viewModel: CystemViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CystemTheme(
        accentArgb = state.accentArgb,
        darkTheme = state.darkMode,
    ) {
        Box(Modifier.fillMaxSize()) {
            CystemShell(state, viewModel)
            AnimatedVisibility(
                visible = state.bootVisible,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                BootSequence(onSkip = viewModel::skipBoot)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CystemShell(
    state: CystemUiState,
    viewModel: CystemViewModel,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Drawer navigation remains available from the menu button; avoid installing swipe gesture handling on the root surface.
        drawerContent = {
            ConversationDrawer(
                state = state,
                viewModel = viewModel,
                onClose = { scope.launch { drawerState.close() } },
            )
        },
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "CYSTEM",
                                fontWeight = FontWeight.Black,
                                letterSpacing = 2.sp,
                            )
                            Text(
                                text = state.stage ?: "PRIVATE SYSTEM",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (state.processing) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } },
                        ) {
                            Text("☰", fontSize = 22.sp)
                        }
                    },
                    actions = {
                        Text(
                            text = "LOCAL",
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    },
                )
            },
        ) { padding ->
            SystemConsole(
                state = state,
                onDraftChange = viewModel::setDraft,
                onSend = viewModel::sendDraft,
                onStop = viewModel::stopGeneration,
                onDismissError = viewModel::clearError,
                onConfirmTool = viewModel::confirmTool,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun ConversationDrawer(
    state: CystemUiState,
    viewModel: CystemViewModel,
    onClose: () -> Unit,
) {
    var renameTarget by remember { mutableStateOf<com.cystem.core.model.Conversation?>(null) }
    var renameValue by remember { mutableStateOf("") }

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.width(328.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "CYSTEM",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Black,
                    letterSpacing = 4.sp,
                ),
            )
            Text(
                "LOCAL COMMAND CENTER",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    viewModel.newConversation()
                    onClose()
                },
            ) {
                Text("+  NEW SESSION")
            }
            HorizontalDivider()
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(state.conversations, key = { it.id }) { conversation ->
                    NavigationDrawerItem(
                        label = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    conversation.title,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (conversation.pinned) Text("•")
                            }
                        },
                        selected = conversation.id == state.activeConversationId,
                        onClick = {
                            viewModel.selectConversation(conversation.id)
                            onClose()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    renameTarget?.let { conversation ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename session") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it.take(120) },
                    singleLine = true,
                    label = { Text("Title") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameConversation(conversation.id, renameValue)
                        renameTarget = null
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SystemConsole(
    state: CystemUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onDismissError: () -> Unit,
    onConfirmTool: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            GridBackdrop(Modifier.fillMaxSize())

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    SystemStatusCard(
                        processing = state.processing,
                        stage = state.stage,
                    )
                }

                items(state.messages, key = { it.id }) { message ->
                    MessageCard(message)
                }

                if (state.streamingText.isNotBlank() || state.processing) {
                    item {
                        StreamingCard(
                            text = state.streamingText,
                            reasoning = state.streamingReasoning,
                            stage = state.stage,
                        )
                    }
                }

                if (state.sources.isNotEmpty()) {
                    item {
                        SourcesCard(state.sources.map { it.title to it.url })
                    }
                }

                if (state.messages.isEmpty() && !state.processing) {
                    item { EmptyState() }
                }
            }
        }

        state.pendingConfirmation?.let { confirmation ->
            ConfirmationCard(
                confirmation = confirmation,
                onConfirm = { onConfirmTool(true) },
                onCancel = { onConfirmTool(false) },
            )
        }

        AnimatedVisibility(
            visible = state.error != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            state.error?.let { message ->
                ErrorBanner(
                    message = message,
                    onDismiss = onDismissError,
                )
            }
        }

        Composer(
            value = state.draft,
            onValueChange = onDraftChange,
            onSend = onSend,
            enabled = !state.processing && state.pendingConfirmation == null,
        )
    }
}

@Composable
private fun ConfirmationCard(
    confirmation: com.cystem.core.coordinator.ToolConfirmation,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .animateContentSize(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "CONFIRM ACTION",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
            )
            Text(confirmation.prompt)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                TextButton(onClick = onConfirm) { Text("Confirm") }
            }
        }
    }
}

@Composable
private fun SystemStatusCard(
    processing: Boolean,
    stage: String?,
) {
    val alpha by animateFloatAsState(
        targetValue = if (processing) 1f else 0.88f,
        animationSpec = spring(
            dampingRatio = 0.8f,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "statusAlpha",
    )

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.82f * alpha),
        tonalElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "SYSTEM " + if (processing) "ACTIVE" else "READY",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 3.sp,
                )
                Text(
                    stage ?: "PRIVATE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Private local state • provider requests only when invoked • no analytics",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MessageCard(message: Message) {
    val user = message.role == MessageRole.USER
    val background = if (user) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f)
    } else {
        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.88f)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = RoundedCornerShape(
                topStart = 24.dp,
                topEnd = 24.dp,
                bottomStart = if (user) 24.dp else 6.dp,
                bottomEnd = if (user) 6.dp else 24.dp,
            ),
            color = background,
            tonalElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (user) "YOU" else "CYSTEM",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (user) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                )
                SelectableBodyText(message.content)
                if (!message.reasoning.isNullOrBlank()) {
                    Surface(
                        modifier = Modifier.animateContentSize(),
                        color = MaterialTheme.colorScheme.background.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "REASONING",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            Text(
                                message.reasoning,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamingCard(
    text: String,
    reasoning: String,
    stage: String?,
) {
    Surface(
        modifier = Modifier.animateContentSize(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "CYSTEM",
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                )
                Text(
                    stage ?: "STREAMING",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (text.isBlank()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                SelectableBodyText(text + " ▌")
            }

            if (reasoning.isNotBlank()) {
                Surface(
                    modifier = Modifier.animateContentSize(),
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.40f),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "THINKING STREAM",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        Text(
                            reasoning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcesCard(sources: List<Pair<String, String>>) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.78f),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "SOURCES",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
            )
            sources.take(8).forEach { (title, url) ->
                Text(
                    text = "• " + title + "\n" + url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxWidth().height(180.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "No active session messages",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Ask anything or use /search, /image or /new in the composer.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
        tonalElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                maxLines = 6,
                placeholder = { Text("Talk to the system…") },
                shape = RoundedCornerShape(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Surface(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(enabled = enabled, onClick = onSend),
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(20.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "↑",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 24.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectableBodyText(value: String) {
    Text(
        text = AnnotatedString(value),
        style = MaterialTheme.typography.bodyLarge.copy(
            lineHeight = 25.sp,
        ),
    )
}

@Composable
private fun GridBackdrop(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
            Brush.radialGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.11f),
                    MaterialTheme.colorScheme.background.copy(alpha = 0f),
                ),
            ),
        ),
    )
}

@Composable
private fun BootSequence(onSkip: () -> Unit) {
    val pulse by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(
            dampingRatio = 0.55f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "bootPulse",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF17182A),
                        Color(0xFF07080D),
                    ),
                ),
            )

        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "CYSTEM",
                fontSize = 46.sp * pulse,
                fontWeight = FontWeight.Black,
                letterSpacing = 8.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "INITIALIZING PRIVATE SYSTEM",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 2.sp,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(28.dp))
            Text(
                "STARTING…",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
