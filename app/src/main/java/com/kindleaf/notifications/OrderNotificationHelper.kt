package com.kindleaf.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kindleaf.MainActivity
import com.kindleaf.R
import com.kindleaf.data.NotificationSettingsStore
import com.kindleaf.models.NotificationEventKeys

object OrderNotificationHelper {

    const val CHANNEL_ID = "kindleaf_orders"
    private const val CHANNEL_NAME = "Orders"
    private var nextId = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Order and account alerts"
        }
        manager.createNotificationChannel(channel)
    }

    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun showOrderPlaced(context: Context, orderId: String, paidOnline: Boolean = false) {
        if (!NotificationSettingsStore.isEnabled(NotificationEventKeys.ORDER_PLACED)) return
        val shortId = orderId.take(8).uppercase()
        show(
            context = context,
            title = if (paidOnline) "Payment received" else "Order placed",
            body = if (paidOnline) "Payment received. Your order #$shortId has been placed."
            else "Thank you! Your order #$shortId has been placed.",
            requestCode = orderId.hashCode(),
            orderId = orderId,
        )
    }

    fun showOrderCancelled(context: Context, orderId: String, detail: String? = null) {
        if (!NotificationSettingsStore.isEnabled(NotificationEventKeys.ORDER_CANCELLED)) return
        val shortId = orderId.take(8).uppercase()
        show(
            context = context,
            title = "Order Cancelled",
            body = detail?.takeIf { it.isNotBlank() }
                ?: "Your order #$shortId has been cancelled.",
            requestCode = orderId.hashCode() xor 0xCA11,
            orderId = orderId,
        )
    }

    fun showLeafPointsChanged(context: Context, added: Boolean, points: Int) {
        val key = if (added) NotificationEventKeys.LEAF_POINTS_ADDED
        else NotificationEventKeys.LEAF_POINTS_DEDUCTED
        if (!NotificationSettingsStore.isEnabled(key)) return
        show(
            context = context,
            title = if (added) "Leaf Points Added" else "Leaf Points Deducted",
            body = if (added) "$points Leaf Points were added to your account."
            else "$points Leaf Points were deducted from your account.",
            requestCode = (if (added) 0xADD else 0xDED) xor points,
        )
    }

    fun show(
        context: Context,
        title: String,
        body: String,
        requestCode: Int = nextId,
        orderId: String? = null,
    ) {
        ensureChannel(context)
        if (!hasPermission(context)) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (orderId != null) {
                action = Intent.ACTION_VIEW
                data = Uri.parse("kindleaf://order/$orderId")
            }
        }
        val pending = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        // Re-checked here (not only in hasPermission) so the call site is provably guarded.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        ) {
            try {
                NotificationManagerCompat.from(context).notify(nextId++, notification)
            } catch (_: SecurityException) {
                // Permission revoked between the check and the call.
            }
        }
    }
}
