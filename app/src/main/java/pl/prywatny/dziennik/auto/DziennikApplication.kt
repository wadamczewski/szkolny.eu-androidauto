package pl.prywatny.dziennik.auto

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import pl.prywatny.dziennik.auto.messages.MessageCheckWorker
import pl.prywatny.dziennik.auto.messages.MessageNotifier

class DziennikApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        MessageNotifier.createChannel(this)
        MessageCheckWorker.schedule(this)
    }
}

/** Opens the app's launcher activity (phone app, or the car activity on Automotive OS). */
object MainActivityLauncher {
    fun pendingIntent(context: Context): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
