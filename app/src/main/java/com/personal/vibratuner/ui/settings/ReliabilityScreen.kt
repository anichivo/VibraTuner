package com.personal.vibratuner.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.personal.vibratuner.R
import com.personal.vibratuner.core.platform.PlatformCompat
import com.personal.vibratuner.theme.AppTheme
import com.personal.vibratuner.ui.components.AppCard
import com.personal.vibratuner.ui.components.AppOutlinedButton

@Composable
fun ReliabilityScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val dimens = AppTheme.dimens
    val lifecycleOwner = LocalLifecycleOwner.current

    var canScheduleExact by remember { mutableStateOf(PlatformCompat.canScheduleExactAlarms(context)) }
    var hasNotifications by remember { mutableStateOf(PlatformCompat.hasNotificationPermission(context)) }
    var isIgnoringBattery by remember { mutableStateOf(PlatformCompat.isIgnoringBatteryOptimizations(context)) }

    // Обновление статусов при возврате на экран из настроек системы
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            canScheduleExact = PlatformCompat.canScheduleExactAlarms(context)
            hasNotifications = PlatformCompat.hasNotificationPermission(context)
            isIgnoringBattery = PlatformCompat.isIgnoringBatteryOptimizations(context)
        }
    }

    val isAndroid13Plus = remember { PlatformCompat.isExactAlarmFixedBySystem() }
    val isNotificationPermissionRequired = remember { PlatformCompat.canRequestNotificationPermission() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .consumeWindowInsets(contentPadding)
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(dimens.spacingMd)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text = stringResource(R.string.reliability_title),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Spacer(modifier = Modifier.height(dimens.spacingLg))

        // 1. Точные будильники
        val exactAlarmTitle = stringResource(R.string.reliability_exact_alarm_title)
        val exactAlarmStatus = if (isAndroid13Plus) {
            stringResource(R.string.reliability_exact_alarm_granted_fixed)
        } else if (canScheduleExact) {
            stringResource(R.string.reliability_exact_alarm_granted)
        } else {
            stringResource(R.string.reliability_exact_alarm_denied)
        }
        val exactAlarmDesc = if (isAndroid13Plus || canScheduleExact) {
            stringResource(R.string.reliability_exact_alarm_desc_granted)
        } else {
            stringResource(R.string.reliability_exact_alarm_desc_denied)
        }
        val exactAlarmGranted = isAndroid13Plus || canScheduleExact

        StatusCard(
            icon = Icons.Default.Alarm,
            title = exactAlarmTitle,
            status = exactAlarmStatus,
            isSuccess = exactAlarmGranted,
            description = exactAlarmDesc,
            buttonText = if (!isAndroid13Plus) stringResource(R.string.reliability_btn_grant_exact) else null,
            onButtonClick = { PlatformCompat.openExactAlarmSettings(context) }
        )

        Spacer(modifier = Modifier.height(dimens.spacingMd))

        // 2. Уведомления
        val notifTitle = stringResource(R.string.reliability_notifications_title)
        val notifStatus = if (!isNotificationPermissionRequired) {
            stringResource(R.string.reliability_notifications_not_needed)
        } else if (hasNotifications) {
            stringResource(R.string.reliability_notifications_granted)
        } else {
            stringResource(R.string.reliability_notifications_denied)
        }
        val notifDesc = if (!isNotificationPermissionRequired) {
            stringResource(R.string.reliability_notifications_desc_android12)
        } else if (hasNotifications) {
            stringResource(R.string.reliability_notifications_desc_granted)
        } else {
            stringResource(R.string.reliability_notifications_desc_denied)
        }
        val notifSuccess = !isNotificationPermissionRequired || hasNotifications

        StatusCard(
            icon = Icons.Default.Notifications,
            title = notifTitle,
            status = notifStatus,
            isSuccess = notifSuccess,
            description = notifDesc,
            buttonText = if (isNotificationPermissionRequired && !hasNotifications) {
                stringResource(R.string.reliability_btn_open_settings)
            } else null,
            onButtonClick = { PlatformCompat.openNotificationSettings(context) }
        )

        Spacer(modifier = Modifier.height(dimens.spacingMd))

        // 3. Оптимизация батареи
        val batteryTitle = stringResource(R.string.reliability_battery_title)
        val batteryStatus = if (isIgnoringBattery) {
            stringResource(R.string.reliability_battery_disabled)
        } else {
            stringResource(R.string.reliability_battery_enabled)
        }
        val batteryDesc = if (isIgnoringBattery) {
            stringResource(R.string.reliability_battery_desc_disabled)
        } else {
            stringResource(R.string.reliability_battery_desc_enabled)
        }

        StatusCard(
            icon = Icons.Default.BatteryAlert,
            title = batteryTitle,
            status = batteryStatus,
            isSuccess = isIgnoringBattery,
            description = batteryDesc,
            buttonText = stringResource(R.string.reliability_btn_battery_settings),
            onButtonClick = { PlatformCompat.openBatteryOptimizationSettings(context) }
        )
    }
}

@Composable
private fun StatusCard(
    icon: ImageVector,
    title: String,
    status: String,
    isSuccess: Boolean,
    description: String,
    buttonText: String?,
    onButtonClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dimens = AppTheme.dimens

    AppCard(modifier = modifier.fillMaxWidth()) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )

                Spacer(modifier = Modifier.padding(start = dimens.spacingSm))

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = status,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(dimens.spacingSm))

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (buttonText != null) {
                Spacer(modifier = Modifier.height(dimens.spacingMd))
                AppOutlinedButton(
                    text = buttonText,
                    onClick = onButtonClick,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
