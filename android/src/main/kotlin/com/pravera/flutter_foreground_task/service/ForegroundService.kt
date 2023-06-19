package com.pravera.flutter_foreground_task.service

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.*
import android.net.wifi.WifiManager
import android.os.*
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.pravera.flutter_foreground_task.R
import com.pravera.flutter_foreground_task.models.ForegroundServiceAction
import com.pravera.flutter_foreground_task.models.ForegroundServiceStatus
import com.pravera.flutter_foreground_task.models.ForegroundTaskOptions
import com.pravera.flutter_foreground_task.models.NotificationOptions
import com.pravera.flutter_foreground_task.utils.ForegroundServiceUtils
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.loader.FlutterLoader
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.FlutterCallbackInformation
import kotlinx.coroutines.*
import java.util.*
import kotlin.system.exitProcess


/**
 * A service class for implementing foreground service.
 *
 * @author Dev-hwang
 * @version 1.0
 */
class ForegroundService : Service(), MethodChannel.MethodCallHandler {
    companion object {
        private val TAG = ForegroundService::class.java.simpleName
        private const val ACTION_TASK_START = "onStart"
        private const val ACTION_TASK_EVENT = "onEvent"
        private const val ACTION_TASK_DESTROY = "onDestroy"
        private const val ACTION_BUTTON_PRESSED = "onButtonPressed"
        private const val ACTION_NOTIFICATION_PRESSED = "onNotificationPressed"
        private const val ACTION_DATE_CHANGED = "onDateChanged"
        private const val DATA_FIELD_NAME = "data"

        /** Returns whether the foreground service is running. */
        var isRunningService = false
            private set
    }

    private lateinit var foregroundServiceStatus: ForegroundServiceStatus
    private lateinit var foregroundTaskOptions: ForegroundTaskOptions
    private lateinit var notificationOptions: NotificationOptions

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var currFlutterLoader: FlutterLoader? = null
    private var prevFlutterEngine: FlutterEngine? = null
    private var currFlutterEngine: FlutterEngine? = null
    private var backgroundChannel: MethodChannel? = null
    private var backgroundJob: Job? = null

    // A broadcast receiver that handles intents that occur within the foreground service.
    private var broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                val action = intent?.action ?: return
                val data = intent.getStringExtra(DATA_FIELD_NAME)
                backgroundChannel?.invokeMethod(action, data)
            } catch (e: Exception) {
                Log.e(TAG, "onReceive", e)
            }
        }
    }
    private var dateChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                Log.d("dateChangedReceiver", "ACTION_DATE_CHANGED")
                val action = intent?.action ?: return
                val data = intent.getStringExtra(DATA_FIELD_NAME)
                backgroundChannel?.invokeMethod(ACTION_DATE_CHANGED, data)
            } catch (e: Exception) {
                Log.e(TAG, "onReceive", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fetchDataFromPreferences()
        registerBroadcastReceiver()

        when (foregroundServiceStatus.action) {
            ForegroundServiceAction.START -> {
                startForegroundService()
                executeDartCallback(foregroundTaskOptions.callbackHandle)
            }
            ForegroundServiceAction.REBOOT -> {
                startForegroundService()
                executeDartCallback(foregroundTaskOptions.callbackHandleOnBoot)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        fetchDataFromPreferences()

        when (foregroundServiceStatus.action) {
            ForegroundServiceAction.UPDATE -> {
                startForegroundService()
                executeDartCallback(foregroundTaskOptions.callbackHandle)
            }
            ForegroundServiceAction.RESTART -> {
                startForegroundService()
                executeDartCallback(foregroundTaskOptions.callbackHandleOnBoot)
            }
            ForegroundServiceAction.STOP -> {
                stopForegroundService()
                return START_NOT_STICKY
            }
        }

        return if (notificationOptions.isSticky) START_STICKY else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseLockMode()
        destroyBackgroundChannel()
        unregisterBroadcastReceiver()
        if (foregroundServiceStatus.action != ForegroundServiceAction.STOP) {
            if (isSetStopWithTaskFlag()) {
                exitProcess(0)
            } else {
                Log.i(TAG, "The foreground service was terminated due to an unexpected problem.")
                if (notificationOptions.isSticky) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (!ForegroundServiceUtils.isIgnoringBatteryOptimizations(
                                applicationContext
                            )
                        ) {
                            Log.i(
                                TAG,
                                "Turn off battery optimization to restart service in the background."
                            )
                            return
                        }
                    }
                    setRestartAlarm()
                }
            }
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "initialize" -> startForegroundTask()
            else -> result.notImplemented()
        }
    }

    private fun fetchDataFromPreferences() {
        foregroundServiceStatus = ForegroundServiceStatus.getData(applicationContext)
        foregroundTaskOptions = ForegroundTaskOptions.getData(applicationContext)
        notificationOptions = NotificationOptions.getData(applicationContext)
    }

    private fun registerBroadcastReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(ACTION_BUTTON_PRESSED)
            addAction(ACTION_NOTIFICATION_PRESSED)
        }
        registerReceiver(broadcastReceiver, intentFilter)
        val dateIntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }
        registerReceiver(dateChangedReceiver, dateIntentFilter)
    }

    private fun unregisterBroadcastReceiver() {
        unregisterReceiver(broadcastReceiver)
    }

    @SuppressLint("WrongConstant")
    private fun startForegroundService() {
        // Get the icon and PendingIntent to put in the notification.
        Log.d("show today", "${notificationOptions.showToday}")
        if (!notificationOptions.showToday) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notificationOptions.id)
            Log.d("startForegroundService", "cancellllllllllllllll")
            startForegroundServicePray()
            return
        }
        val pm = applicationContext.packageManager
        val iconResType = notificationOptions.iconData?.resType
        val iconResPrefix = notificationOptions.iconData?.resPrefix
        val iconName = notificationOptions.iconData?.name
        var iconBackgroundColor: Int? = null
        val iconBackgroundColorRgb = notificationOptions.iconData?.backgroundColorRgb?.split(",")
        if ((iconBackgroundColorRgb != null) && (iconBackgroundColorRgb.size == 3)) {
            iconBackgroundColor = Color.rgb(
                iconBackgroundColorRgb[0].toInt(),
                iconBackgroundColorRgb[1].toInt(),
                iconBackgroundColorRgb[2].toInt()
            )
        }
        val iconResId = if (iconResType.isNullOrEmpty()
            || iconResPrefix.isNullOrEmpty()
            || iconName.isNullOrEmpty()
        ) {
            getAppIconResourceId(pm)
        } else {
            getDrawableResourceId(
                iconResType, iconResPrefix, iconName
            )
        }
//        Log.d("iconId", "$iconResId")
//        Log.d("drawId", "${R.drawable.a10}")
        ////large date
        val largeIconResType = notificationOptions.largeIconData?.resType
        val largeIconResPrefix = notificationOptions.largeIconData?.resPrefix
        val largeIconName = notificationOptions.largeIconData?.name
        var largeIconBackgroundColor: Int? = null
        val largeIconBackgroundColorRgb =
            notificationOptions.largeIconData?.backgroundColorRgb?.split(",")
        if (largeIconBackgroundColorRgb != null && largeIconBackgroundColorRgb.size == 3) {
            largeIconBackgroundColor = Color.rgb(
                largeIconBackgroundColorRgb[0].toInt(),
                largeIconBackgroundColorRgb[1].toInt(),
                largeIconBackgroundColorRgb[2].toInt()
            )
        }
        val largeIconResId = if (largeIconResType.isNullOrEmpty()
            || largeIconResPrefix.isNullOrEmpty()
            || largeIconName.isNullOrEmpty()
        ) {
            getAppIconResourceId(pm)
        } else {
            getDrawableResourceId(
                largeIconResType,
                largeIconResPrefix,
                largeIconName
            )
        }

        val pendingIntent = getPendingIntent(pm)
        val notificationLayout = RemoteViews(packageName, R.layout.today_layout)
        // Create a notification and start the foreground service.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                notificationOptions.channelId,
                notificationOptions.channelName,
                notificationOptions.channelImportance
            )
            channel.description = notificationOptions.channelDescription
            channel.enableVibration(notificationOptions.enableVibration)
            if (!notificationOptions.playSound) {
                channel.setSound(null, null)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)

            var subtitle = notificationOptions.todayNotificationData?.hijri?:""
            if (!notificationOptions.todayNotificationData?.gregorian.isNullOrEmpty())
                subtitle += " - " + notificationOptions.todayNotificationData?.gregorian!!
            notificationLayout.setImageViewBitmap(
                R.id.today_title,
                text2Bitmap(
                    notificationOptions.todayNotificationData?.solar!!,
                    subtitle,
                    notificationOptions.todayNotificationData?.titleColor!!,
                    notificationOptions.todayNotificationData?.subtitleColor!!,
                )
            )
            notificationLayout.setImageViewResource(R.id.today_subtitle, iconResId)
            val nightModeFlags: Int = resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK
            val iconColor = when (nightModeFlags) {
                Configuration.UI_MODE_NIGHT_YES -> Color.WHITE
                Configuration.UI_MODE_NIGHT_NO -> iconBackgroundColor
                Configuration.UI_MODE_NIGHT_UNDEFINED -> iconBackgroundColor
                else -> iconBackgroundColor
            }
            notificationLayout.setInt(
                R.id.today_subtitle, "setColorFilter",
                iconColor!!
            )
            val builder = Notification.Builder(this, notificationOptions.channelId)
            builder.setOngoing(true)
            builder.setShowWhen(notificationOptions.showWhen)
            builder.setSmallIcon(iconResId)
            builder.setLargeIcon(
                BitmapFactory.decodeResource(
                    resources,
                    largeIconResId
                )
            )
            builder.setContentIntent(pendingIntent)
            builder.setCustomContentView(notificationLayout)
            builder.setCustomBigContentView(notificationLayout)
//            builder.setContentTitle(notificationOptions.contentTitle)
//            builder.setContentText(notificationOptions.contentText)
            builder.setVisibility(notificationOptions.visibility)
//            if (iconBackgroundColor != null) {
//                builder.setColor(iconBackgroundColor)
//            }
            for (action in buildButtonActions()) {
                builder.addAction(action)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }
            builder.setGroup("Calendar")
            startForeground(notificationOptions.id, builder.build())
        } else {
            val builder = NotificationCompat.Builder(this, notificationOptions.channelId)
            builder.setOngoing(true)
            builder.setShowWhen(notificationOptions.showWhen)
            builder.setSmallIcon(iconResId)
            builder.setContentIntent(pendingIntent)
            builder.setLargeIcon(
                BitmapFactory.decodeResource(
                    resources,
                    largeIconResId
                )
            )
            builder.setCustomContentView(notificationLayout)
            builder.setCustomBigContentView(notificationLayout)
//            builder.setContentTitle(notificationOptions.contentTitle)
//            builder.setContentText(notificationOptions.contentText)
            builder.setVisibility(notificationOptions.visibility)
            if (iconBackgroundColor != null) {
                builder.color = iconBackgroundColor
            }
            if (!notificationOptions.enableVibration) {
                builder.setVibrate(longArrayOf(0L))
            }
            if (!notificationOptions.playSound) {
                builder.setSound(null)
            }
            builder.priority = notificationOptions.priority
            for (action in buildButtonCompatActions()) {
                builder.addAction(action)
            }
            builder.setGroup("Calendar")
            startForeground(notificationOptions.id, builder.build())
            /////////////////////
            ////pray part////////
            /////////////////////

            /////////////////////
            /////////////////////
            /////////////////////
        }

        acquireLockMode()
        isRunningService = true
        Log.d("startForegroundService", "continueeeeeeeeeeeeeeeeeeeeeee")
        startForegroundServicePray()
    }

    @SuppressLint("WrongConstant")
    private fun startForegroundServicePray() {
        Log.d("foreg1", "line 360")
        // Get the icon and PendingIntent to put in the notification.
        if (!notificationOptions.showPray) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notificationOptions.prayId())
            return
        }
        val pm = applicationContext.packageManager
        val iconResId = getDrawableResourceId("drawable", "", "ic_launcher_white")
//        Log.d("iconId", "$iconResId")
//        Log.d("drawId", "${R.drawable.a10}")
        ////large date

        val largeIconResId = getDrawableResourceId("drawable", "", "ic_launcher")


        val pendingIntent = getPendingIntent(pm)
        val notificationLayout = RemoteViews(packageName, R.layout.pray_layout_v2)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Create a notification and start the foreground service.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                notificationOptions.channelId + " Pray",
                notificationOptions.channelName+ " Pray",
                notificationOptions.channelImportance
            )
            channel.description = notificationOptions.channelDescription
            channel.enableVibration(notificationOptions.enableVibration)
            if (!notificationOptions.playSound) {
                channel.setSound(null, null)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)

            var subtitle = notificationOptions.todayNotificationData?.hijri?:""
            if (!notificationOptions.todayNotificationData?.gregorian.isNullOrEmpty())
                subtitle += " - " + notificationOptions.todayNotificationData?.gregorian!!
            val fontSize = 36f
            notificationLayout.setImageViewBitmap(
                R.id.title_v2_1,
                text2Bitmap3(
                    notificationOptions.prayNotificationData?.titles?.get(0) ?: "",
                    notificationOptions.todayNotificationData?.titleColor!!,
                    fontSize
                )
            )
            notificationLayout.setImageViewBitmap(
                R.id.title_v2_2,
                text2Bitmap3(
                    notificationOptions.prayNotificationData?.titles?.get(1) ?: "",
                    notificationOptions.todayNotificationData?.titleColor!!,
                    fontSize
                )
            )
            notificationLayout.setImageViewBitmap(
                R.id.title_v2_3,
                text2Bitmap3(
                    notificationOptions.prayNotificationData?.titles?.get(2) ?: "",
                    notificationOptions.todayNotificationData?.titleColor!!,
                    fontSize
                )
            )
            notificationLayout.setImageViewBitmap(
                R.id.title_v2_4,
                text2Bitmap3(
                    notificationOptions.prayNotificationData?.titles?.get(3) ?: "",
                    notificationOptions.todayNotificationData?.titleColor!!,
                    fontSize
                )
            )
            notificationLayout.setImageViewBitmap(
                R.id.title_v2_5,
                text2Bitmap3(
                    notificationOptions.prayNotificationData?.titles?.get(4) ?: "",
                    notificationOptions.todayNotificationData?.titleColor!!,
                    fontSize
                )
            )

            notificationLayout.setImageViewBitmap(
                R.id.text_v2_1,
                text2Bitmap3(
                    notificationOptions.prayNotificationData?.times?.get(0) ?: "",
                    notificationOptions.todayNotificationData?.subtitleColor!!,
                    fontSize
                )
            )
            notificationLayout.setImageViewBitmap(
                R.id.text_v2_2,
                text2Bitmap3(
                    notificationOptions.prayNotificationData?.times?.get(1) ?: "",
                    notificationOptions.todayNotificationData?.subtitleColor!!,
                    fontSize
                )
            )
            notificationLayout.setImageViewBitmap(
                R.id.text_v2_3,
                text2Bitmap3(
                    notificationOptions.prayNotificationData?.times?.get(2) ?: "",
                    notificationOptions.todayNotificationData?.subtitleColor!!,
                    fontSize
                )
            )
            notificationLayout.setImageViewBitmap(
                R.id.text_v2_4,
                text2Bitmap3(
                    notificationOptions.prayNotificationData?.times?.get(3) ?: "",
                    notificationOptions.todayNotificationData?.subtitleColor!!,
                    fontSize
                )
            )
            notificationLayout.setImageViewBitmap(
                R.id.text_v2_5,
                text2Bitmap3(
                    notificationOptions.prayNotificationData?.times?.get(4) ?: "",
                    notificationOptions.todayNotificationData?.subtitleColor!!,
                    fontSize
                )
            )

//            notificationLayout.setImageViewBitmap(
//                R.id.title_1,
//                text2Bitmap2(
//                    notificationOptions.prayNotificationData?.titles?.get(0) ?: "",
//                    notificationOptions.prayNotificationData?.times?.get(0) ?: "",
//                    notificationOptions.todayNotificationData?.titleColor!!,
//                    notificationOptions.todayNotificationData?.subtitleColor!!,
//                )
//            )
//            notificationLayout.setImageViewBitmap(
//                R.id.title_2,
//                text2Bitmap2(
//                    notificationOptions.prayNotificationData?.titles?.get(1) ?: "",
//                    notificationOptions.prayNotificationData?.times?.get(1) ?: "",
//                    notificationOptions.todayNotificationData?.titleColor!!,
//                    notificationOptions.todayNotificationData?.subtitleColor!!,
//                )
//            )
//            notificationLayout.setImageViewBitmap(
//                R.id.title_3,
//                text2Bitmap2(
//                    notificationOptions.prayNotificationData?.titles?.get(2) ?: "",
//                    notificationOptions.prayNotificationData?.times?.get(2) ?: "",
//                    notificationOptions.todayNotificationData?.titleColor!!,
//                    notificationOptions.todayNotificationData?.subtitleColor!!,
//                )
//            )
//            notificationLayout.setImageViewBitmap(
//                R.id.title_4,
//                text2Bitmap2(
//                    notificationOptions.prayNotificationData?.titles?.get(3) ?: "",
//                    notificationOptions.prayNotificationData?.times?.get(3) ?: "",
//                    notificationOptions.todayNotificationData?.titleColor!!,
//                    notificationOptions.todayNotificationData?.subtitleColor!!,
//                )
//            )
//            notificationLayout.setImageViewBitmap(
//                R.id.title_5,
//                text2Bitmap2(
//                    notificationOptions.prayNotificationData?.titles?.get(4) ?: "",
//                    notificationOptions.prayNotificationData?.times?.get(4) ?: "",
//                    notificationOptions.todayNotificationData?.titleColor!!,
//                    notificationOptions.todayNotificationData?.subtitleColor!!,
//                )
//            )
            val builder = Notification.Builder(this, notificationOptions.channelId + " Pray")
            builder.setOngoing(true)
            builder.setShowWhen(notificationOptions.showWhen)
            builder.setSmallIcon(iconResId)
            builder.setLargeIcon(
                BitmapFactory.decodeResource(
                    resources,
                    largeIconResId
                )
            )
            builder.setContentIntent(pendingIntent)
            builder.setCustomContentView(notificationLayout)
            builder.setCustomBigContentView(notificationLayout)
//            builder.setContentTitle(notificationOptions.contentTitle)
//            builder.setContentText(notificationOptions.contentText)
            builder.setVisibility(notificationOptions.visibility)
//            if (iconBackgroundColor != null) {
//                builder.setColor(iconBackgroundColor)
//            }
            for (action in buildButtonActions()) {
                builder.addAction(action)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }
            Log.d("foreg1", "line 431")
            builder.setGroup("Pray")
            if (notificationOptions.showToday)
                nm.notify(notificationOptions.prayId(), builder.build())
            else
                startForeground(notificationOptions.prayId(), builder.build())
        } else {
            val builder = NotificationCompat.Builder(this, notificationOptions.channelId + " Pray")
            builder.setOngoing(true)
            builder.setShowWhen(notificationOptions.showWhen)
            builder.setSmallIcon(iconResId)
            builder.setContentIntent(pendingIntent)
            builder.setGroup("Pray")
            builder.setLargeIcon(
                BitmapFactory.decodeResource(
                    resources,
                    largeIconResId
                )
            )
            builder.color = Color.WHITE
            builder.setCustomContentView(notificationLayout)
            builder.setCustomBigContentView(notificationLayout)
//            builder.setContentTitle(notificationOptions.contentTitle)
//            builder.setContentText(notificationOptions.contentText)
            builder.setVisibility(notificationOptions.visibility)
            if (!notificationOptions.enableVibration) {
                builder.setVibrate(longArrayOf(0L))
            }
            if (!notificationOptions.playSound) {
                builder.setSound(null)
            }
            builder.priority = notificationOptions.priority
            for (action in buildButtonCompatActions()) {
                builder.addAction(action)
            }
            if (notificationOptions.showToday)
                nm.notify(notificationOptions.prayId(), builder.build())
            else
                startForeground(notificationOptions.prayId(), builder.build())
            /////////////////////
            ////pray part////////
            /////////////////////

            /////////////////////
            /////////////////////
            /////////////////////
        }
        acquireLockMode()
        isRunningService = true
    }

    private fun stopForegroundService() {
        releaseLockMode()
        stopForeground(false)
        stopSelf()
        isRunningService = false
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLockMode() {
        if (foregroundTaskOptions.allowWakeLock && (wakeLock == null || wakeLock?.isHeld == false)) {
            wakeLock =
                (applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                    newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "ForegroundService:WakeLock"
                    ).apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }
        }

        if (foregroundTaskOptions.allowWifiLock && (wifiLock == null || wifiLock?.isHeld == false)) {
            wifiLock =
                (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).run {
                    createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "ForegroundService:WifiLock"
                    ).apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }
        }
    }

    private fun releaseLockMode() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }

        wifiLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
    }

    private fun setRestartAlarm() {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            add(Calendar.SECOND, 1)
        }

        val intent = Intent(this, RestartReceiver::class.java)
        val sender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getBroadcast(this, 0, intent, 0)
        }

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, sender)
    }

    private fun isSetStopWithTaskFlag(): Boolean {
        val pm = applicationContext.packageManager
        val cName = ComponentName(this, this.javaClass)
        val flags = pm.getServiceInfo(cName, PackageManager.GET_META_DATA).flags
        return (flags and ServiceInfo.FLAG_STOP_WITH_TASK) == 1
    }

    private fun initBackgroundChannel() {
        if (backgroundChannel != null) destroyBackgroundChannel()

        currFlutterEngine = FlutterEngine(this)

        currFlutterLoader = FlutterInjector.instance().flutterLoader()
        currFlutterLoader?.startInitialization(this)
        currFlutterLoader?.ensureInitializationComplete(this, null)

        val messenger = currFlutterEngine?.dartExecutor?.binaryMessenger ?: return
        backgroundChannel = MethodChannel(messenger, "flutter_foreground_task/background")
        backgroundChannel?.setMethodCallHandler(this)
    }

    private fun executeDartCallback(callbackHandle: Long?) {
        // If there is no callback handle, the code below will not be executed.
        if (callbackHandle == null) return

        initBackgroundChannel()

        val bundlePath = currFlutterLoader?.findAppBundlePath() ?: return
        val callbackInfo = FlutterCallbackInformation.lookupCallbackInformation(callbackHandle)
        val dartCallback = DartExecutor.DartCallback(assets, bundlePath, callbackInfo)
        currFlutterEngine?.dartExecutor?.executeDartCallback(dartCallback)
    }

    private fun startForegroundTask() {
        stopForegroundTask()

        val callback = object : MethodChannel.Result {
            override fun success(result: Any?) {
                backgroundJob = CoroutineScope(Dispatchers.Default).launch {
                    do {
                        withContext(Dispatchers.Main) {
                            try {
                                backgroundChannel?.invokeMethod(ACTION_TASK_EVENT, null)
                            } catch (e: Exception) {
                                Log.e(TAG, "invokeMethod", e)
                            }
                        }

                        delay(foregroundTaskOptions.interval)
                    } while (!foregroundTaskOptions.isOnceEvent)
                }
            }

            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {}

            override fun notImplemented() {}
        }
        backgroundChannel?.invokeMethod(ACTION_TASK_START, null, callback)
    }

    private fun stopForegroundTask() {
        backgroundJob?.cancel()
        backgroundJob = null
    }

    private fun destroyBackgroundChannel() {
        stopForegroundTask()

        currFlutterLoader = null
        prevFlutterEngine = currFlutterEngine
        currFlutterEngine = null

        val callback = object : MethodChannel.Result {
            override fun success(result: Any?) {
                prevFlutterEngine?.destroy()
                prevFlutterEngine = null
            }

            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                prevFlutterEngine?.destroy()
                prevFlutterEngine = null
            }

            override fun notImplemented() {
                prevFlutterEngine?.destroy()
                prevFlutterEngine = null
            }
        }
        backgroundChannel?.invokeMethod(ACTION_TASK_DESTROY, null, callback)
        backgroundChannel?.setMethodCallHandler(null)
        backgroundChannel = null
    }

    private fun getDrawableResourceId(
        resType: String,
        resPrefix: String,
        name: String,
    ): Int {
        var resName = if (resPrefix.contains("ic")) {
            String.format("ic_%s", name)
        } else {
            String.format("img_%s", name)
        }
        resName = name
        // Log.d("icon", resName)
        return applicationContext.resources.getIdentifier(
            resName,
            resType,
            applicationContext.packageName
        )
    }

    private fun getAppIconResourceId(pm: PackageManager): Int {
        return try {
            val appInfo =
                pm.getApplicationInfo(applicationContext.packageName, PackageManager.GET_META_DATA)
            appInfo.icon
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "getAppIconResourceId", e)
            0
        }
    }

    private fun getPendingIntent(pm: PackageManager): PendingIntent {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
            || ForegroundServiceUtils.canDrawOverlays(applicationContext)
        ) {
            val pressedIntent = Intent(ACTION_NOTIFICATION_PRESSED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.getBroadcast(
                    this, 20000, pressedIntent, PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getBroadcast(this, 20000, pressedIntent, 0)
            }
        } else {
            val launchIntent = pm.getLaunchIntentForPackage(applicationContext.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.getActivity(
                    this, 20000, launchIntent, PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getActivity(this, 20000, launchIntent, 0)
            }
        }
    }

    private fun buildButtonActions(): List<Notification.Action> {
        val actions = mutableListOf<Notification.Action>()
        val buttons = notificationOptions.buttons
        for (i in buttons.indices) {
            val bIntent = Intent(ACTION_BUTTON_PRESSED).apply {
                putExtra(DATA_FIELD_NAME, buttons[i].id)
            }
            val bPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.getBroadcast(this, i + 1, bIntent, PendingIntent.FLAG_IMMUTABLE)
            } else {
                PendingIntent.getBroadcast(this, i + 1, bIntent, 0)
            }
            val bAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Notification.Action.Builder(null, buttons[i].text, bPendingIntent).build()
            } else {
                Notification.Action.Builder(0, buttons[i].text, bPendingIntent).build()
            }
            actions.add(bAction)
        }

        return actions
    }

    private fun buildButtonCompatActions(): List<NotificationCompat.Action> {
        val actions = mutableListOf<NotificationCompat.Action>()
        val buttons = notificationOptions.buttons
        for (i in buttons.indices) {
            val bIntent = Intent(ACTION_BUTTON_PRESSED).apply {
                putExtra(DATA_FIELD_NAME, buttons[i].id)
            }
            val bPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.getBroadcast(this, i + 1, bIntent, PendingIntent.FLAG_IMMUTABLE)
            } else {
                PendingIntent.getBroadcast(this, i + 1, bIntent, 0)
            }
            val bAction =
                NotificationCompat.Action.Builder(0, buttons[i].text, bPendingIntent).build()
            actions.add(bAction)
        }

        return actions
    }

    private fun text2Bitmap(
        title: String,
        subtitle: String,
        titleColorInt: String,
        subtitleColorInt: String
    ): Bitmap {
        val typeface: Typeface = Typeface.createFromAsset(assets, "iransans_reg.ttf")
        val nightModeFlags: Int = resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK
        val tc = Color.parseColor(titleColorInt)
        val stc = Color.parseColor(subtitleColorInt)
        val titleColor = when (nightModeFlags) {
            Configuration.UI_MODE_NIGHT_YES -> Color.WHITE
            Configuration.UI_MODE_NIGHT_NO -> tc
            Configuration.UI_MODE_NIGHT_UNDEFINED -> tc
            else -> tc
        }
        val subtitleColor = when (nightModeFlags) {
            Configuration.UI_MODE_NIGHT_YES -> Color.WHITE
            Configuration.UI_MODE_NIGHT_NO -> stc
            Configuration.UI_MODE_NIGHT_UNDEFINED -> stc
            else -> stc
        }
        val paint = Paint()
        paint.isAntiAlias = true
        paint.isSubpixelText = true
        paint.typeface = typeface
        paint.style = Paint.Style.FILL
        paint.color = titleColor
        paint.textSize = 56f
        paint.textAlign = Paint.Align.RIGHT

        val paint2 = Paint()
        paint2.isAntiAlias = true
        paint2.isSubpixelText = true
        paint2.typeface = typeface
        paint2.style = Paint.Style.FILL
        paint2.color = subtitleColor
        paint2.textSize = 40f
        paint2.textAlign = Paint.Align.RIGHT
        val rect = Rect()
        paint.getTextBounds(title, 0, title.length, rect)

        val height = rect.height()
        val width = rect.width()
        val rect2 = Rect()
        paint2.getTextBounds(subtitle, 0, subtitle.length, rect2)
        val f: Float = resources.displayMetrics.scaledDensity
        val i3 = (f * 10.0f).toInt()
        val height2 = rect2.height()
        val width2 = rect2.width()
        val i4 = (if (width > width2) width else width2)
        val i5 = i4 - width
        val createBitmap =
            Bitmap.createBitmap(i4, height2 + height + i3 + 10, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(createBitmap)

        canvas.drawText(title, (width + i5).toFloat(), (-paint.fontMetrics.ascent), paint)
        canvas.drawText(
            subtitle,
            (width2 + ((i4 - width2) / 2)).toFloat(),
            (((height + 16) + i3)).toFloat() + 24f,
            paint2
        )

        return createBitmap
    }

    private fun text2Bitmap3(
        title: String,
        titleColorInt: String,
        fontSize: Float,
    ): Bitmap {
        val typeface: Typeface = Typeface.createFromAsset(assets, "iransans_reg.ttf")
        val nightModeFlags: Int = resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK
        val tc = Color.parseColor(titleColorInt)

        val titleColor = when (nightModeFlags) {
            Configuration.UI_MODE_NIGHT_YES -> Color.WHITE
            Configuration.UI_MODE_NIGHT_NO -> tc
            Configuration.UI_MODE_NIGHT_UNDEFINED -> tc
            else -> tc
        }

        val paint = Paint()
        paint.isAntiAlias = true
        paint.isSubpixelText = true
        paint.typeface = typeface
        paint.style = Paint.Style.FILL
        paint.color = titleColor
        paint.textSize = fontSize
        paint.textAlign = Paint.Align.RIGHT


        val rect = Rect()
        paint.getTextBounds(title, 0, title.length, rect)

        val height = rect.height()
        val width = rect.width()
        val rect2 = Rect()

        val f: Float = resources.displayMetrics.scaledDensity
        val i3 = (f * 10.0f).toInt()
        val height2 = rect2.height()
        val width2 = rect2.width()
        val i4 = (if (width > width2) width else width2)
        val i5 = i4 - width
        val createBitmap =
            Bitmap.createBitmap(i4, height2 + height + i3 + 10, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(createBitmap)
        canvas.drawText(title, (width).toFloat(), (-paint.fontMetrics.ascent), paint)
        return createBitmap
    }
}