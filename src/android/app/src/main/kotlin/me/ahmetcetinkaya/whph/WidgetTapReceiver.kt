package me.ahmetcetinkaya.whph

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import es.antonborri.home_widget.HomeWidgetBackgroundReceiver
import es.antonborri.home_widget.HomeWidgetPlugin
import org.json.JSONArray
import org.json.JSONObject

/**
 * First stop for widget checkbox taps.
 *
 * For tasks it responds instantly on the native side (ticks the row, plays the
 * completion sound) and records the task id in a persistent pending queue
 * before handing off to the Flutter background callback. The Flutter side
 * completes every queued id and clears it, so a completion is never lost even
 * if the OS kills the background isolate before it finishes.
 */
class WidgetTapReceiver : BroadcastReceiver() {
  companion object {
    private const val TAG = "WidgetTapReceiver"
    const val PENDING_KEY = "pending_task_completions"
    private const val DATA_KEY = "widget_data"
    private const val HOME_WIDGET_BACKGROUND_ACTION = "es.antonborri.home_widget.action.BACKGROUND"

    fun pendingTaskIds(context: Context): Set<String> {
      val raw = HomeWidgetPlugin.getData(context).getString(PENDING_KEY, null) ?: return emptySet()
      return try {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { arr.getString(it) }.toSet()
      } catch (e: Exception) {
        emptySet()
      }
    }
  }

  override fun onReceive(context: Context, intent: Intent) {
    val uri = intent.data
    val action = uri?.getQueryParameter("action")
    val itemId = uri?.getQueryParameter("itemId")

    if (action == "toggle_task" && !itemId.isNullOrEmpty()) {
      val firstTap = markTaskPending(context, itemId)
      if (!firstTap) return // already being completed; ignore repeat taps

      playCompletionSound(context)
      refreshTaskWidgets(context)
    }

    // Hand off to the Flutter background callback for the real database write.
    val forward =
      Intent(context, HomeWidgetBackgroundReceiver::class.java).apply {
        this.action = HOME_WIDGET_BACKGROUND_ACTION
        data = uri
      }
    context.sendBroadcast(forward)
  }

  /** Returns false if the task was already pending. */
  private fun markTaskPending(context: Context, taskId: String): Boolean {
    val prefs = HomeWidgetPlugin.getData(context)
    synchronized(WidgetTapReceiver::class.java) {
      val pending =
        try {
          JSONArray(prefs.getString(PENDING_KEY, "[]"))
        } catch (e: Exception) {
          JSONArray()
        }
      for (i in 0 until pending.length()) {
        if (pending.optString(i) == taskId) return false
      }
      pending.put(taskId)

      val editor = prefs.edit().putString(PENDING_KEY, pending.toString())

      // Tick the row in the cached widget data so the change shows immediately.
      prefs.getString(DATA_KEY, null)?.let { dataString ->
        try {
          val data = JSONObject(dataString)
          val tasks = data.optJSONArray("tasks")
          if (tasks != null) {
            for (i in 0 until tasks.length()) {
              val task = tasks.optJSONObject(i) ?: continue
              if (task.optString("id") == taskId) task.put("isCompleted", true)
            }
            editor.putString(DATA_KEY, data.toString())
          }
        } catch (e: Exception) {
          Log.w(TAG, "Could not update cached widget data", e)
        }
      }
      editor.commit()
    }
    return true
  }

  private fun refreshTaskWidgets(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    val ids = manager.getAppWidgetIds(ComponentName(context, WhphTasksWidgetProvider::class.java))
    manager.notifyAppWidgetViewDataChanged(ids, R.id.tasks_widget_list)
  }

  private fun playCompletionSound(context: Context) {
    try {
      val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
      if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return

      val afd = context.resources.openRawResourceFd(R.raw.widget_task_done) ?: return
      val player = MediaPlayer()
      player.setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .build(),
      )
      player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
      afd.close()
      player.setOnCompletionListener { it.release() }
      player.setOnErrorListener { mp, _, _ ->
        mp.release()
        true
      }
      player.prepare()
      player.start()
    } catch (e: Exception) {
      Log.w(TAG, "Could not play completion sound", e)
    }
  }
}
