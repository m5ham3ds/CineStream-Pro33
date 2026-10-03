package com.example.ui.screens.profile

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.CanonicalSubscriptionSource
import com.example.data.model.CanonicalSubscriptionTier
import com.example.data.model.FeatureState
import com.example.data.model.FeaturesConfig
import com.example.data.repository.EconomyConfigRepository
import com.example.data.repository.PointsRepository
import com.example.data.repository.UserSecurityManager
import com.example.ui.theme.SuccessGreen
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.DailyLoginState
import com.example.data.model.LeaderboardEntry
import com.example.data.model.PointTransaction
import com.example.data.model.PointWallet
import com.example.data.model.RewardTask
import com.example.data.model.RewardedAdState
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.CalendarMonth

/**
 * PHASE 03D: CANONICAL SUBSCRIPTION & POINTS EARNING SCREEN
 *
 * Strict Architectural Rule:
 * Subscription Benefit = REMOVE_ADS ONLY.
 * Subscription does NOT unlock or restrict video quality.
 * All qualities from the source (including 1080p and 4K) are available to ALL tiers.
 *
 * Points Invariant:
 * Zero client economic authority. Authoritative server reads only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionScreen(
    onBack: () -> Unit,
    pointsViewModel: PointsEarningViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }

    val restrictions by UserSecurityManager.restrictionsFlow.collectAsState()
    val featuresConfig by EconomyConfigRepository.featuresConfig.collectAsState()
    val economyConfig by EconomyConfigRepository.economyConfig.collectAsState()

    val operationMessage by pointsViewModel.operationMessage.collectAsState()
    LaunchedEffect(operationMessage) {
        operationMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            pointsViewModel.clearMessage()
        }
    }

    val currentTier = restrictions.canonicalTier
    val isAdFree = restrictions.isAdFree
    val isExpired = restrictions.isSubscriptionExpired && currentTier != CanonicalSubscriptionTier.FREE

    val expiryFormatted = remember(restrictions.subscriptionExpiresAt) {
        restrictions.subscriptionExpiresAt?.let {
            SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(it))
        }
    }

    val sourceLabel = remember(restrictions.subscriptionSource) {
        when (restrictions.subscriptionSource.uppercase()) {
            "MONEY" -> context.getString(R.string.source_money)
            "POINTS" -> context.getString(R.string.source_points)
            "ADMIN_GRANT" -> context.getString(R.string.source_admin_grant)
            "LEGACY" -> context.getString(R.string.source_legacy)
            else -> restrictions.subscriptionSource
        }
    }

    // Pro Request submission dialog state
    var selectedPlanForRequest by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var paymentRefText by remember { mutableStateOf("") }
    var notesText by remember { mutableStateOf("") }
    var isSubmittingRequest by remember { mutableStateOf(false) }

    // Points Subscription Redemption Confirmation Dialog state (Phase 03F)
    var selectedPlanForRedemption by remember { mutableStateOf<Triple<String, String, Long>?>(null) }
    val isRedeemingSubscription by pointsViewModel.isRedeemingSubscription.collectAsState()
    val walletState by pointsViewModel.wallet.collectAsState()

    // Points Subscription Redemption Confirmation Dialog
    if (selectedPlanForRedemption != null) {
        val (sku, planTitle, cost) = selectedPlanForRedemption!!
        val currentBalance = walletState.pointsBalance
        val balanceAfter = currentBalance - cost
        val hasSufficientBalance = currentBalance >= cost

        AlertDialog(
            onDismissRequest = {
                if (!isRedeemingSubscription) selectedPlanForRedemption = null
            },
            title = {
                Text(
                    text = stringResource(R.string.redeem_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.redeem_dialog_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.redeem_selected_plan), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(planTitle, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.redeem_cost), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("⭐ $cost نقطة", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.redeem_current_balance), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("⭐ $currentBalance نقطة", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.redeem_balance_after), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = if (hasSufficientBalance) "⭐ $balanceAfter نقطة" else "رصيد غير كافٍ",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (hasSufficientBalance) SuccessGreen else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    if (!hasSufficientBalance) {
                        Text(
                            text = stringResource(R.string.redeem_insufficient_warning, currentBalance, cost),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pointsViewModel.redeemSubscription(sku) { success, _ ->
                            if (success) {
                                selectedPlanForRedemption = null
                            }
                        }
                    },
                    enabled = hasSufficientBalance && !isRedeemingSubscription,
                    modifier = Modifier.testTag("confirm_redeem_button")
                ) {
                    if (isRedeemingSubscription) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.redeem_confirm_action))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { selectedPlanForRedemption = null },
                    enabled = !isRedeemingSubscription
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Pro Request Dialog
    if (selectedPlanForRequest != null) {
        val (planId, durationDays) = selectedPlanForRequest!!
        AlertDialog(
            onDismissRequest = {
                if (!isSubmittingRequest) selectedPlanForRequest = null
            },
            title = {
                Text(
                    text = stringResource(R.string.pro_request_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.pro_request_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = paymentRefText,
                        onValueChange = { paymentRefText = it },
                        label = { Text(stringResource(R.string.payment_ref_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = notesText,
                        onValueChange = { notesText = it },
                        label = { Text(stringResource(R.string.notes_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isSubmittingRequest = true
                        coroutineScope.launch {
                            val res = PointsRepository.submitProRequest(
                                planId = planId,
                                durationDays = durationDays,
                                paymentReference = paymentRefText.trim(),
                                notes = notesText.trim()
                            )
                            isSubmittingRequest = false
                            selectedPlanForRequest = null
                            if (res.isSuccess) {
                                Toast.makeText(context, context.getString(R.string.request_submitted_success), Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Error: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = !isSubmittingRequest
                ) {
                    if (isSubmittingRequest) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.submit_request))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { selectedPlanForRequest = null },
                    enabled = !isSubmittingRequest
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.subscription),
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
                ScrollableTabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    edgePadding = 16.dp
                ) {
                    Tab(
                        selected = selectedTabIndex == 0,
                        onClick = { selectedTabIndex = 0 },
                        modifier = Modifier.testTag("tab_subscriptions"),
                        text = { Text(stringResource(R.string.tab_subscriptions), fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = selectedTabIndex == 1,
                        onClick = { selectedTabIndex = 1 },
                        modifier = Modifier.testTag("tab_earn_points"),
                        text = { Text(stringResource(R.string.tab_earn_points), fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = selectedTabIndex == 2,
                        onClick = { selectedTabIndex = 2 },
                        modifier = Modifier.testTag("tab_leaderboard"),
                        text = { Text(stringResource(R.string.tab_leaderboard), fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = selectedTabIndex == 3,
                        onClick = { selectedTabIndex = 3 },
                        modifier = Modifier.testTag("tab_points_ledger"),
                        text = { Text(stringResource(R.string.tab_points_ledger), fontWeight = FontWeight.Bold) }
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (selectedTabIndex == 0) {
            // 1. Mandatory Core Business Rule Banner
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "ميزة الاشتراك: إزالة الإعلانات فقط",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.quality_all_tiers_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 2. Active Subscription Status Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.current_plan),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = when (currentTier) {
                                    CanonicalSubscriptionTier.FREE -> stringResource(R.string.free_title)
                                    CanonicalSubscriptionTier.PRO_LITE -> stringResource(R.string.pro_lite_title)
                                    CanonicalSubscriptionTier.PRO -> stringResource(R.string.pro_title)
                                },
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = when {
                                isAdFree -> SuccessGreen.copy(alpha = 0.2f)
                                isExpired -> MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        ) {
                            Text(
                                text = when {
                                    isAdFree -> "نشط • بدون إعلانات"
                                    isExpired -> stringResource(R.string.subscription_expired)
                                    else -> "مجاني • مدعوم بالإعلانات"
                                },
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    isAdFree -> SuccessGreen
                                    isExpired -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }

                    if (expiryFormatted != null && currentTier != CanonicalSubscriptionTier.FREE) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (isAdFree) {
                                stringResource(R.string.subscription_expires_on, expiryFormatted)
                            } else {
                                stringResource(R.string.subscription_expired)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isAdFree) SuccessGreen else MaterialTheme.colorScheme.error
                        )
                    }

                    if (currentTier != CanonicalSubscriptionTier.FREE) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.subscription_source_label, sourceLabel),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 3. Available Plans Catalog
            Text(
                text = stringResource(R.string.choose_plan),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))

            // FREE Plan Card
            CanonicalPlanCard(
                tierName = stringResource(R.string.free_title),
                durationLabel = "دائم",
                pointsCost = null,
                isCurrent = currentTier == CanonicalSubscriptionTier.FREE && !isExpired,
                features = listOf(
                    "مشاهدة مدعومة بالإعلانات",
                    "جميع جودات المصدر متاحة (1080p, 4K)",
                    "التحميل متاح لجميع الجودات المتوفرة"
                ),
                onRequestClick = null,
                onRedeemClick = null
            )

            Spacer(modifier = Modifier.height(16.dp))

            // PRO LITE Section
            Text(
                text = "باقات PRO LITE",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))

            // PRO LITE 1 Day
            val costLite1d = economyConfig.redemptionCosts["pro_lite_1d"] ?: 50L
            CanonicalPlanCard(
                tierName = "PRO LITE",
                durationLabel = stringResource(R.string.duration_1_day),
                pointsCost = costLite1d,
                isCurrent = currentTier == CanonicalSubscriptionTier.PRO_LITE && isAdFree && restrictions.durationDays == 1,
                features = listOf(
                    "إزالة الإعلانات بالكامل لمدة 24 ساعة",
                    "جميع جودات المصدر متاحة (1080p, 4K)",
                    "التحميل متاح لجميع الجودات المتوفرة"
                ),
                onRequestClick = {
                    selectedPlanForRequest = Pair("pro_lite_1d", 1)
                },
                onRedeemClick = {
                    handlePointsRedeemClicked(context, featuresConfig) {
                        selectedPlanForRedemption = Triple("pro_lite_1d", "PRO LITE (يوم واحد)", costLite1d)
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // PRO LITE 7 Days
            val costLite7d = economyConfig.redemptionCosts["pro_lite_7d"] ?: 250L
            CanonicalPlanCard(
                tierName = "PRO LITE",
                durationLabel = stringResource(R.string.duration_7_days),
                pointsCost = costLite7d,
                isCurrent = currentTier == CanonicalSubscriptionTier.PRO_LITE && isAdFree && restrictions.durationDays == 7,
                features = listOf(
                    "إزالة الإعلانات بالكامل لمدة 7 أيام",
                    "جميع جودات المصدر متاحة (1080p, 4K)",
                    "التحميل متاح لجميع الجودات المتوفرة"
                ),
                onRequestClick = {
                    selectedPlanForRequest = Pair("pro_lite_7d", 7)
                },
                onRedeemClick = {
                    handlePointsRedeemClicked(context, featuresConfig) {
                        selectedPlanForRedemption = Triple("pro_lite_7d", "PRO LITE (7 أيام)", costLite7d)
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // PRO LITE 10 Days
            val costLite1d0 = economyConfig.redemptionCosts["pro_lite_10d"] ?: 350L
            CanonicalPlanCard(
                tierName = "PRO LITE",
                durationLabel = stringResource(R.string.duration_10_days),
                pointsCost = costLite1d0,
                isCurrent = currentTier == CanonicalSubscriptionTier.PRO_LITE && isAdFree && restrictions.durationDays == 10,
                features = listOf(
                    "إزالة الإعلانات بالكامل لمدة 10 أيام",
                    "جميع جودات المصدر متاحة (1080p, 4K)",
                    "التحميل متاح لجميع الجودات المتوفرة"
                ),
                onRequestClick = {
                    selectedPlanForRequest = Pair("pro_lite_10d", 10)
                },
                onRedeemClick = {
                    handlePointsRedeemClicked(context, featuresConfig) {
                        selectedPlanForRedemption = Triple("pro_lite_10d", "PRO LITE (10 أيام)", costLite1d0)
                    }
                }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // PRO Section
            Text(
                text = "باقة PRO الكاملة",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))

            // PRO 30 Days
            val costPro30d = economyConfig.redemptionCosts["pro_30d"] ?: 1000L
            CanonicalPlanCard(
                tierName = "PRO",
                durationLabel = stringResource(R.string.duration_30_days),
                pointsCost = costPro30d,
                isCurrent = currentTier == CanonicalSubscriptionTier.PRO && isAdFree,
                features = listOf(
                    "إزالة الإعلانات بالكامل لمدة 30 يوم",
                    "جميع جودات المصدر متاحة (1080p, 4K)",
                    "التحميل متاح لجميع الجودات المتوفرة"
                ),
                onRequestClick = {
                    selectedPlanForRequest = Pair("pro_30d", 30)
                },
                onRedeemClick = {
                    handlePointsRedeemClicked(context, featuresConfig) {
                        selectedPlanForRedemption = Triple("pro_30d", "PRO (30 يوم)", costPro30d)
                    }
                }
            )

            Spacer(modifier = Modifier.height(32.dp))
            } else if (selectedTabIndex == 1) {
                PointsEarningTab(
                    viewModel = pointsViewModel,
                    featuresConfig = featuresConfig
                )
            } else if (selectedTabIndex == 2) {
                LeaderboardTab(
                    viewModel = pointsViewModel,
                    featuresConfig = featuresConfig
                )
            } else if (selectedTabIndex == 3) {
                PointsLedgerTab(
                    viewModel = pointsViewModel
                )
            }
        }
    }
}

private fun handlePointsRedeemClicked(
    context: android.content.Context,
    featuresConfig: FeaturesConfig,
    onProceed: () -> Unit
) {
    if (featuresConfig.points == FeatureState.COMING_SOON || featuresConfig.subscriptions == FeatureState.COMING_SOON) {
        Toast.makeText(context, FeaturesConfig.COMING_SOON_MESSAGE, Toast.LENGTH_LONG).show()
    } else if (featuresConfig.points == FeatureState.DISABLED || featuresConfig.subscriptions == FeatureState.DISABLED) {
        Toast.makeText(context, featuresConfig.disabledMessage, Toast.LENGTH_LONG).show()
    } else {
        onProceed()
    }
}

@Composable
private fun CanonicalPlanCard(
    tierName: String,
    durationLabel: String,
    pointsCost: Long?,
    isCurrent: Boolean,
    features: List<String>,
    onRequestClick: (() -> Unit)?,
    onRedeemClick: (() -> Unit)?
) {
    val borderColor = if (isCurrent) SuccessGreen else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(if (isCurrent) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = tierName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = durationLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (isCurrent) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = SuccessGreen.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = "✓ " + stringResource(R.string.current_plan),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = SuccessGreen
                        )
                    }
                } else if (pointsCost != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = stringResource(R.string.points_cost, pointsCost),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            features.forEach { feat ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 3.dp)
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (isCurrent) SuccessGreen else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = feat,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!isCurrent && (onRequestClick != null || onRedeemClick != null)) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (onRequestClick != null) {
                        Button(
                            onClick = onRequestClick,
                            modifier = Modifier.weight(1f).height(42.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                stringResource(R.string.submit_request),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (onRedeemClick != null) {
                        OutlinedButton(
                            onClick = onRedeemClick,
                            modifier = Modifier.weight(1f).height(42.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                stringResource(R.string.redeem_with_points),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PointsEarningTab(
    viewModel: PointsEarningViewModel,
    featuresConfig: FeaturesConfig
) {
    val context = LocalContext.current
    val wallet by viewModel.wallet.collectAsState()
    val dailyLogin by viewModel.dailyLoginState.collectAsState()
    val rewardedAdState by viewModel.rewardedAdState.collectAsState()
    val tasks by viewModel.tasks.collectAsState()
    val taskClaims by viewModel.taskClaims.collectAsState()

    val isClaimingDaily by viewModel.isClaimingDailyLogin.collectAsState()
    val isWatchingAd by viewModel.isWatchingRewardedAd.collectAsState()
    val claimingTaskId by viewModel.claimingTaskId.collectAsState()

    // Feature Gate Banner
    if (featuresConfig.points == FeatureState.COMING_SOON) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = FeaturesConfig.COMING_SOON_MESSAGE,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    } else if (featuresConfig.points == FeatureState.DISABLED) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = featuresConfig.disabledMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }

    // 1. Authoritative Point Wallet Card
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.points_balance_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "⭐ ${wallet.pointsBalance}",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFD700)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = SuccessGreen.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "حساب موثق من الخادم",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = SuccessGreen,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.total_earned_title),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "+${wallet.totalPointsEarned}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = SuccessGreen
                    )
                }
                Column {
                    Text(
                        text = stringResource(R.string.total_spent_title),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "-${wallet.totalPointsSpent}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    // 2. Daily Login Card
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.daily_login_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = stringResource(R.string.daily_streak_label, dailyLogin.currentStreak),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            // 7-day ladder display
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val ladder = dailyLogin.rewardLadder
                val currentDayIndex = dailyLogin.currentStreak % (if (ladder.isNotEmpty()) ladder.size else 7)
                ladder.take(7).forEachIndexed { index, reward ->
                    val isCurrent = index == currentDayIndex && !dailyLogin.todayClaimed
                    val isPassed = index < currentDayIndex || (index == currentDayIndex && dailyLogin.todayClaimed)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "يوم ${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = when {
                                isPassed -> SuccessGreen.copy(alpha = 0.2f)
                                isCurrent -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                            border = if (isCurrent) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "+$reward",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        isPassed -> SuccessGreen
                                        isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { viewModel.claimDailyLogin() },
                enabled = !dailyLogin.todayClaimed && !isClaimingDaily && featuresConfig.dailyLogin != FeatureState.DISABLED,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("claim_daily_login_button"),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (isClaimingDaily) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else if (dailyLogin.todayClaimed) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.daily_login_claimed))
                } else {
                    Text("${stringResource(R.string.claim_daily_login)} (+${dailyLogin.nextReward})")
                }
            }
        }
    }

    // 3. Rewarded Ads Card
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.rewarded_ads_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = stringResource(R.string.rewarded_ads_cap_label, rewardedAdState.dailyWatchedCount, rewardedAdState.dailyCap),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "+${rewardedAdState.rewardPerAd} نقطة لكل إعلان مكتمل",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = { viewModel.watchRewardedAd(context) },
                enabled = rewardedAdState.isEligible && !isWatchingAd && featuresConfig.rewardedAds != FeatureState.DISABLED,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("watch_rewarded_ad_button"),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (isWatchingAd) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else if (rewardedAdState.cooldownSecondsRemaining > 0) {
                    Text(stringResource(R.string.ad_cooldown_label, rewardedAdState.cooldownSecondsRemaining))
                } else if (rewardedAdState.dailyWatchedCount >= rewardedAdState.dailyCap) {
                    Text("تم الوصول للحد اليومي (${rewardedAdState.dailyCap})")
                } else {
                    Text("${stringResource(R.string.watch_ad_action)} (+${rewardedAdState.rewardPerAd})")
                }
            }
        }
    }

    // 4. Reward Tasks Card
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.TaskAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.tasks_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            if (tasks.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_tasks),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                tasks.forEach { task ->
                    val isClaimed = taskClaims[task.taskId] == true
                    val isClaimingThis = claimingTaskId == task.taskId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = task.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (task.description.isNotBlank()) {
                                Text(
                                    text = task.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        ) {
                            Text(
                                text = "+${task.rewardPoints}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Button(
                            onClick = { viewModel.claimTaskReward(task) },
                            enabled = !isClaimed && !isClaimingThis && featuresConfig.tasks != FeatureState.DISABLED,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            if (isClaimingThis) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else if (isClaimed) {
                                Text(stringResource(R.string.task_claimed_label), style = MaterialTheme.typography.labelSmall)
                            } else {
                                Text(stringResource(R.string.claim_task_action), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                }
            }
        }
    }
}

@Composable
private fun LeaderboardTab(viewModel: PointsEarningViewModel, featuresConfig: FeaturesConfig) {
    val leaderboard by viewModel.leaderboard.collectAsState()

    if (featuresConfig.leaderboard == FeatureState.COMING_SOON) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary)
        ) {
            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = FeaturesConfig.COMING_SOON_MESSAGE,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = Color(0xFFFFD700))
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "لوحة المتصدرين الأسبوعية",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "من الإثنين إلى الأحد (UTC)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            if (leaderboard.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_leaderboard),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                leaderboard.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = when (entry.rank) {
                                    1 -> Color(0xFFFFD700).copy(alpha = 0.2f)
                                    2 -> Color(0xFFC0C0C0).copy(alpha = 0.2f)
                                    3 -> Color(0xFFCD7F32).copy(alpha = 0.2f)
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "#${entry.rank}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = when (entry.rank) {
                                            1 -> Color(0xFFFFD700)
                                            2 -> Color(0xFFC0C0C0)
                                            3 -> Color(0xFFCD7F32)
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = entry.displayName.ifBlank { entry.username.ifBlank { "User" } },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (entry.reward.isNotBlank()) {
                                    Text(
                                        text = "المكافأة: ${entry.reward}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        Text(
                            text = "${entry.weeklyEarnedPoints} نقطة",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFFD700)
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                }
            }
        }
    }
}

@Composable
private fun PointsLedgerTab(viewModel: PointsEarningViewModel) {
    val transactions by viewModel.transactions.collectAsState()

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "سجل العمليات الموثق",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "سجل المعاملات الصادر من الخادم (قراءة فقط)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            if (transactions.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_transactions),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                transactions.forEach { tx ->
                    val isPositive = tx.amount >= 0
                    val dateFormatted = remember(tx.createdAt) {
                        if (tx.createdAt > 0) {
                            SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(tx.createdAt))
                        } else ""
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when (tx.type) {
                                    "DAILY_LOGIN" -> "مكافأة تسجيل يومي"
                                    "REWARDED_AD" -> "مشاهدة إعلان"
                                    "TASK_REWARD" -> "مكافأة مهمة"
                                    "LEADERBOARD_REWARD" -> "مكافأة متصدرين"
                                    "SUBSCRIPTION_REDEMPTION" -> "استبدال اشتراك"
                                    "ADMIN_GRANT" -> "منحة إدارية"
                                    else -> tx.type
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (dateFormatted.isNotBlank()) {
                                Text(
                                    text = dateFormatted,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = if (isPositive) "+${tx.amount}" else "${tx.amount}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (isPositive) SuccessGreen else MaterialTheme.colorScheme.error
                            )
                            if (tx.balanceAfter > 0) {
                                Text(
                                    text = "الرصيد: ${tx.balanceAfter}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                }
            }
        }
    }
}

