package com.workoutmaker.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.workoutmaker.app.ui.collectAsStateSafe
import com.workoutmaker.app.ui.components.SectionCard
import com.workoutmaker.app.ui.components.SectionLabel

/**
 * Move and remove Home's cards.
 *
 * Reordering is arrow buttons rather than a drag handle. A long-press drag is
 * the prettier gesture, but on a list this short it is also the one that is
 * undiscoverable, fails on a mis-grab, and fights the screen's own scroll.
 * Two buttons say exactly what they do and can be hit repeatedly.
 *
 * Pinned cards are shown, not omitted: seeing "Readiness, pinned" answers the
 * question "where did readiness go" before it is asked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeHomeScreen(vm: HomeViewModel = hiltViewModel(), onBack: () -> Unit) {
    val layout by vm.homeLayout.collectAsStateSafe()
    val onHome = layout.order.filter { it.pinned || it !in layout.hidden }
    val hidden = layout.order.filter { !it.pinned && it in layout.hidden }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("Home layout")
                Text("Customize home", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Readiness and today's workout are pinned. Everything else you can move or " +
                        "switch off, and anything you switch off stays off until you turn it back on here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard {
                SectionLabel("On your home screen")
                onHome.forEachIndexed { i, card ->
                    CardRow(
                        card = card,
                        visible = true,
                        canMoveUp = !card.pinned && i > 0 && !onHome[i - 1].pinned,
                        canMoveDown = !card.pinned && i < onHome.lastIndex,
                        onMove = { up -> vm.moveHomeCard(card, up) },
                        onToggle = { vm.toggleHomeCard(card) },
                    )
                }
            }

            if (hidden.isNotEmpty()) {
                SectionCard {
                    SectionLabel("Hidden")
                    hidden.forEach { card ->
                        CardRow(
                            card = card,
                            visible = false,
                            canMoveUp = false,
                            canMoveDown = false,
                            onMove = {},
                            onToggle = { vm.toggleHomeCard(card) },
                        )
                    }
                }
            }

            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            TextButton(onClick = { vm.resetHomeLayout() }, modifier = Modifier.fillMaxWidth()) {
                Text("Reset to the default order", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CardRow(
    card: HomeCard,
    visible: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Boolean) -> Unit,
    onToggle: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (card.pinned) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = "Pinned",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Column {
                MoveButton(Icons.Filled.KeyboardArrowUp, "Move ${card.title} up", canMoveUp) { onMove(true) }
                MoveButton(Icons.Filled.KeyboardArrowDown, "Move ${card.title} down", canMoveDown) { onMove(false) }
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(card.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(card.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (card.pinned) {
            Text(
                "Pinned",
                Modifier.clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Switch(checked = visible, onCheckedChange = { onToggle() })
        }
    }
}

@Composable
private fun MoveButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(26.dp)) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
