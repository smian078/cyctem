package com.cystem.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
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
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

@Composable
private fun CystemShell(
    state: CystemUiState,
    viewModel: CystemViewModel,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.width(316.dp),
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
                            scope.launch { drawerState.close() }
                        },
                    ) {
                        Text("+  NEW SESSION")
                    }
                    HorizontalDivider()
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(state.conversations, key = { it.id }) { conversation ->
                            NavigationDrawerItem(
                                label = { Text(conversation.title, maxLines = 2) },
                                selected = conversation.id == state.activeConversationId,
                                onClick = {
                                    viewModel.selectConversation(conversation.id)
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        },
        gesturesEnabled = true,
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("CYSTEM", fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                            Text(
                                "PRIVATE SYSTEM",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                scope.launch { drawerState.open() }
                            },
                        ) {
                            Text("☰")
                        }
                    },
                    actions = {
                        Text(
                            "LOCAL",
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
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun SystemConsole(
    state: CystemUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().imePadding(),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(26.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.82f),
                    tonalElevation = 2.dp,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "SYSTEM READY",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 3.sp,
                        )
                        Text(
                            "CYSTEM keeps conversations, keys and preferences on this device. Network requests leave the phone only when you invoke an enabled model or search service.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (state.activeConversationId == null) {
                item { EmptyState() }
            } else {
                item {
                    Text(
                        "SESSION " + state.activeConversationId.take(8).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Composer(
            value = state.draft,
            onValueChange = onDraftChange,
            onSend = onSend,
        )
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxWidth().height(180.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No active sessions", style = MaterialTheme.typography.titleMedium)
            Text(
                "Open the drawer and create a new system session.",
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
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                maxLines = 6,
                placeholder = { Text("Talk to the system…") },
                shape = RoundedCornerShape(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Surface(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(onClick = onSend),
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(20.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("↑", color = MaterialTheme.colorScheme.onPrimary, fontSize = 24.sp)
                }
            }
        }
    }
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
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onSkip() })
            },
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
                "TAP TO SKIP",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
