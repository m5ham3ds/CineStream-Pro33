package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.compose.runtime.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.screens.notifications.NotificationsViewModel
import androidx.compose.ui.res.stringResource
import com.example.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.text.style.TextOverflow

@Composable
fun CineStreamHeader(
    avatarUrl: String? = null,
    onProfileClick: () -> Unit,
    onSearchClick: () -> Unit,
    onMenuClick: () -> Unit,
    onNotificationsClick: () -> Unit = {},
    notificationsViewModel: NotificationsViewModel = viewModel()
) {
    val unreadCount by notificationsViewModel.unreadCount.collectAsState()
    val notifications by notificationsViewModel.notifications.collectAsState()
    var showNotificationsDropdown by remember { mutableStateOf(false) }

    val brandRed = MaterialTheme.colorScheme.primary
    val bgDark = MaterialTheme.colorScheme.background
    val iconBorder = MaterialTheme.colorScheme.surfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary
    val primary30 = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    val bgColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.surface


    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            // Entire header background with angled red glows
            .drawBehind {
                // Dark base
                drawRect(bgColor)
                
                // Left red diagonal highlight
                val leftPath = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width * 0.25f, 0f)
                    lineTo(size.width * 0.15f, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(
                    path = leftPath,
                    brush = Brush.linearGradient(
                        colors = listOf(primary30, Color.Transparent),
                        start = Offset(0f, size.height / 2),
                        end = Offset(size.width * 0.25f, size.height / 2)
                    )
                )
                
                // Right red diagonal highlight
                val rightPath = Path().apply {
                    moveTo(size.width * 0.85f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width, size.height)
                    lineTo(size.width * 0.75f, size.height)
                    close()
                }
                drawPath(
                    path = rightPath,
                    brush = Brush.linearGradient(
                        colors = listOf(Color.Transparent, primary30),
                        start = Offset(size.width * 0.75f, size.height / 2),
                        end = Offset(size.width, size.height / 2)
                    )
                )
                
                // Top and bottom faint borders
                drawLine(
                    color = surfaceColor,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 2f
                )
                drawLine(
                    color = surfaceColor,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 2f
                )
            }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Profile Icon with Glow
            Box(
                modifier = Modifier.size(60.dp),
                contentAlignment = Alignment.Center
            ) {
                // Outer glow
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), Color.Transparent)
                            ),
                            shape = CircleShape
                        )
                )
                // Inner Circle
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        .clickable(onClick = onProfileClick),
                    contentAlignment = Alignment.Center
                ) {
                    if (!avatarUrl.isNullOrBlank()) {
                        coil.compose.AsyncImage(
                            model = avatarUrl,
                            contentDescription = stringResource(R.string.profile),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = stringResource(R.string.profile),
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            // Logo
            val moviesLabel = stringResource(R.string.movies).uppercase()
            val seriesLabel = stringResource(R.string.series).uppercase()
            val animeLabel = stringResource(R.string.anime).uppercase()
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)) {
                            append("Cine")
                        }
                        withStyle(style = SpanStyle(color = brandRed, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)) {
                            append("Stream")
                        }
                    }
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = buildAnnotatedString {
                        val spacing = 4.sp
                        val grayColor = MaterialTheme.colorScheme.onSurfaceVariant
                        withStyle(style = SpanStyle(color = grayColor, fontSize = 10.sp, letterSpacing = spacing, fontWeight = FontWeight.Bold)) {
                            append(moviesLabel)
                        }
                        withStyle(style = SpanStyle(color = brandRed, fontSize = 11.sp, fontWeight = FontWeight.Black)) {
                            append(" • ")
                        }
                        withStyle(style = SpanStyle(color = grayColor, fontSize = 10.sp, letterSpacing = spacing, fontWeight = FontWeight.Bold)) {
                            append(seriesLabel)
                        }
                        withStyle(style = SpanStyle(color = brandRed, fontSize = 11.sp, fontWeight = FontWeight.Black)) {
                            append(" • ")
                        }
                        withStyle(style = SpanStyle(color = grayColor, fontSize = 10.sp, letterSpacing = spacing, fontWeight = FontWeight.Bold)) {
                            append(animeLabel)
                        }
                    }
                )
            }

            // Actions
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Search
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(bgDark)
                        .border(1.5.dp, iconBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(onClick = onSearchClick) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = stringResource(R.string.search),
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Notifications
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(bgDark)
                        .border(1.5.dp, iconBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(onClick = { showNotificationsDropdown = true }) {
                        Box {
                            Icon(
                                imageVector = Icons.Default.Notifications,
                                contentDescription = stringResource(R.string.notifications),
                                tint = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.size(24.dp)
                            )
                            if (unreadCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 6.dp, y = (-4).dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .background(Brush.radialGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), Color.Transparent)), CircleShape)
                                            .align(Alignment.Center)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                            .align(Alignment.Center),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(if (unreadCount > 9) "9+" else unreadCount.toString(), color = androidx.compose.ui.graphics.Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                                    }
                                }
                            }
                        }
                    }
                    
                    DropdownMenu(
                        expanded = showNotificationsDropdown,
                        onDismissRequest = { showNotificationsDropdown = false },
                        modifier = Modifier
                            .width(300.dp)
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        if (notifications.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.no_notifications_yet), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                onClick = { showNotificationsDropdown = false; onNotificationsClick() }
                            )
                        } else {
                            val formatter = SimpleDateFormat("hh:mm a", Locale.getDefault())
                            notifications.take(3).forEach { notif ->
                                DropdownMenuItem(
                                    text = { 
                                        Column {
                                            Text(notif.title, fontWeight = if (notif.isRead) FontWeight.Normal else FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(notif.message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    },
                                    onClick = {
                                        showNotificationsDropdown = false
                                        onNotificationsClick()
                                    }
                                )
                            }
                            if (notifications.size > 3) {
                                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.view_all_notifications), color = MaterialTheme.colorScheme.primary, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                                    onClick = { showNotificationsDropdown = false; onNotificationsClick() }
                                )
                            }
                        }
                    }
                }

                // Menu
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(bgDark)
                        .border(1.5.dp, iconBorder, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(onClick = onMenuClick) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = stringResource(R.string.menu),
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
        }
    }
}
}