package com.pocketos.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home-screen controls route through the same fail-closed foreground service as the app. */
class ServerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, manager, it, "Ready") }
    }

    companion object {
        fun refreshStatus(context: Context, status: String) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, ServerWidget::class.java)
            manager.getAppWidgetIds(component).forEach { render(context, manager, it, status) }
        }

        private fun render(context: Context, manager: AppWidgetManager, id: Int, status: String) {
            val views = RemoteViews(context.packageName, R.layout.widget)
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val start = PendingIntent.getForegroundService(
                context, 1, Intent(context, ServerService::class.java).setAction(ServerService.ACTION_START),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val stop = PendingIntent.getService(
                context, 2, Intent(context, ServerService::class.java).setAction(ServerService.ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setTextViewText(R.id.widgetStatus, "PocketOS · $status")
            views.setOnClickPendingIntent(R.id.widgetButton, open)
            views.setOnClickPendingIntent(R.id.widgetStartButton, start)
            views.setOnClickPendingIntent(R.id.widgetStopButton, stop)
            manager.updateAppWidget(id, views)
        }
    }
}
