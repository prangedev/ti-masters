package br.com.timasters.app.data.business.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import br.com.timasters.app.MainActivity
import br.com.timasters.app.R
import br.com.timasters.app.data.business.AppointmentDao
import br.com.timasters.app.data.business.AppointmentEntity
import br.com.timasters.app.data.business.AppointmentKind
import br.com.timasters.app.data.business.AppointmentStatus
import br.com.timasters.app.data.business.ClientDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules a notification [AppointmentEntity.reminderMinutes] before each appointment.
 * WorkManager timing is approximate (a few minutes), which is fine for visit reminders and needs
 * no exact-alarm permission.
 */
@Singleton
class AppointmentReminderScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appointmentDao: AppointmentDao
) {
    private val workManager get() = WorkManager.getInstance(context)

    fun update(appointment: AppointmentEntity) {
        val minutes = appointment.reminderMinutes
        val active = appointment.deletedAt == null &&
            AppointmentStatus.from(appointment.status) == AppointmentStatus.SCHEDULED && minutes != null
        val triggerAt = appointment.startsAt - (minutes ?: 0) * 60_000L
        val delay = triggerAt - System.currentTimeMillis()
        if (!active || appointment.startsAt <= System.currentTimeMillis()) {
            cancel(appointment.id)
            return
        }
        val request = OneTimeWorkRequestBuilder<AppointmentReminderWorker>()
            .setInitialDelay(delay.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_APPOINTMENT_ID to appointment.id))
            .build()
        workManager.enqueueUniqueWork(workName(appointment.id), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(appointmentId: String) {
        workManager.cancelUniqueWork(workName(appointmentId))
    }

    /** After a sync, appointments created on other phones also get their reminders here. */
    suspend fun rescheduleAll(companyId: String) {
        appointmentDao.withPendingReminders(companyId, System.currentTimeMillis()).forEach(::update)
    }

    private fun workName(id: String) = "appointment-reminder-$id"

    companion object {
        const val KEY_APPOINTMENT_ID = "appointment_id"
        const val CHANNEL_ID = "agenda"
    }
}

@HiltWorker
class AppointmentReminderWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val appointmentDao: AppointmentDao,
    private val clientDao: ClientDao
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(AppointmentReminderScheduler.KEY_APPOINTMENT_ID) ?: return Result.success()
        val appointment = appointmentDao.get(id) ?: return Result.success()
        // It may have been cancelled, finished or moved since the reminder was scheduled.
        if (appointment.deletedAt != null ||
            AppointmentStatus.from(appointment.status) != AppointmentStatus.SCHEDULED ||
            appointment.startsAt < System.currentTimeMillis() - 60_000L
        ) {
            return Result.success()
        }
        val clientName = clientDao.get(appointment.clientId)?.name.orEmpty()
        showNotification(appointment, clientName)
        return Result.success()
    }

    private fun showNotification(appointment: AppointmentEntity, clientName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createChannel(context)
        val time = DateTimeFormatter.ofPattern("HH:mm")
            .format(Instant.ofEpochMilli(appointment.startsAt).atZone(ZoneId.systemDefault()))
        val kind = context.getString(AppointmentKind.from(appointment.kind).labelResId)
        val openApp = PendingIntent.getActivity(
            context,
            appointment.id.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, AppointmentReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.reminder_notification_title, kind, time))
            .setContentText(listOfNotNull(clientName, appointment.address).joinToString(" · "))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(appointment.id.hashCode(), notification)
    }

    companion object {
        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                AppointmentReminderScheduler.CHANNEL_ID,
                context.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = context.getString(R.string.reminder_channel_description) }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
