package org.melodist.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.melodist.data.CrashGuard

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var forwardingPlayer: QQMusicForwardingPlayer? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    companion object {
        const val ACTION_TOGGLE_FAVORITE = "org.melodist.playback.ACTION_TOGGLE_FAVORITE"
        const val EXTRA_START_FOREGROUND = "org.melodist.playback.EXTRA_START_FOREGROUND"
        const val CHANNEL_ID = "qqmusic_playback_channel_v3"
        const val NOTIFICATION_ID = 1001
    }

    private val sessionCallback =
        object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
            ): MediaSession.ConnectionResult {
                val sessionCommands =
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                        .buildUpon()
                        .add(SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY))
                        .build()
                val isFav = isCurrentSongFavorite()

                @Suppress("DEPRECATION")
                val result =
                    MediaSession.ConnectionResult
                        .AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(sessionCommands)
                        .setCustomLayout(listOf(createFavoriteButton(isFav)))
                        .build()
                return result
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle,
            ): ListenableFuture<SessionResult> {
                if (customCommand.customAction == ACTION_TOGGLE_FAVORITE) {
                    PlaybackManager.toggleCurrentSongFavorite()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                return super.onCustomCommand(session, controller, customCommand, args)
            }
        }

    override fun onCreate() {
        super.onCreate()
        // 服务初始化里的每一步都可能抛（通知渠道、唤醒锁、MediaSession.Builder 的
        // IllegalArgumentException、播放器创建失败……）。这里不兜底的话，异常会在
        // 系统进程里冒泡成 FATAL EXCEPTION，表现为「一点播放就闪退」。
        try {
            setupSession()
        } catch (t: Throwable) {
            Log.e("PlaybackService", "onCreate failed, stopping service", t)
            CrashGuard.record("PlaybackService.onCreate", t)
            stopSelf()
        }
    }

    private fun setupSession() {
        setupNotificationChannel()

        val powerManager = getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        wakeLock = powerManager?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "QQMusic:PlaybackWakeLock")

        val notificationProvider = QQMusicNotificationProvider(this)
        setMediaNotificationProvider(notificationProvider)

        val player = PlaybackManager.getOrCreatePlayer(this)
        val forwarding = QQMusicForwardingPlayer(player)
        forwardingPlayer = forwarding

        val launchIntent =
            packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(Intent.ACTION_MAIN).apply {
                    setPackage(packageName)
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
        launchIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val sessionActivity =
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val isFav = isCurrentSongFavorite()
        val session =
            MediaSession
                .Builder(this, forwarding)
                .setId("QQMusicPlaybackSession")
                .setSessionActivity(sessionActivity)
                .setCallback(sessionCallback)
                .setCustomLayout(listOf(createFavoriteButton(isFav)))
                .build()
        mediaSession = session
        addSession(session)

        // 淡入淡出交接后活动播放器实例变化，需要把会话重新绑到新的播放器上
        PlaybackManager.onActivePlayerSwappedListener = {
            try {
                val newPlayer = PlaybackManager.getOrCreatePlayer(this)
                val newForwarding = QQMusicForwardingPlayer(newPlayer)
                forwardingPlayer = newForwarding
                mediaSession?.player = newForwarding
            } catch (e: Exception) {
                android.util.Log.w("QQMusicPlayback", "Failed to rebind media session player", e)
            }
        }

        serviceScope.launch {
            combine(PlaybackManager.currentSong, PlaybackManager.favoriteSongMids) { song, favs ->
                song != null && favs.contains(song.songMid)
            }.distinctUntilChanged().collect { isFavState ->
                mediaSession?.setCustomLayout(listOf(createFavoriteButton(isFavState)))
            }
        }

        serviceScope.launch {
            combine(
                PlaybackManager.currentSong,
                PlaybackManager.isPlaying,
                PlaybackManager.isTransitioning,
                PlaybackManager.isRemoteActive,
                PlaybackManager.remoteDeviceName,
            ) { song, isPlaying, isTransitioning, isRemote, deviceName ->
                Triple(song?.songMid to (isPlaying to isTransitioning), isRemote, deviceName)
            }.distinctUntilChanged().collect {
                forwardingPlayer?.notifyRemoteStateChanged()
                mediaSession?.let { sessionToUpdate ->
                    onUpdateNotification(sessionToUpdate, PlaybackManager.shouldHoldForeground())
                }
            }
        }

        serviceScope.launch {
            combine(PlaybackManager.isPlaying, PlaybackManager.isLoading) { playing, loading ->
                playing || loading
            }.distinctUntilChanged().collect { shouldHoldWake ->
                updateWakeLock(shouldHoldWake)
            }
        }

        serviceScope.launch {
            PlaybackManager.loopMode.collect {
                forwardingPlayer?.notifyLoopModeChanged()
            }
        }

        PlaybackManager.legacyMetadataSyncCallback = { song, lyrics ->
            syncLegacyMediaMetadata(song, lyrics)
        }

        serviceScope.launch {
            combine(
                PlaybackManager.currentSong,
                PlaybackManager.lyrics,
                PlaybackManager.isPlaying,
            ) { song, lyrics, isPlaying ->
                Triple(song, lyrics, isPlaying)
            }.distinctUntilChanged().collect { (song, lyrics, _) ->
                if (song != null) {
                    syncLegacyMediaMetadata(song, lyrics)
                }
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // startForeground 要越早越好：startForegroundService() 之后 5 秒内没进前台，
        // 系统会抛 ForegroundServiceDidNotStartInTimeException 直接杀进程（doc/14 §7.3）。
        // 所以先抢前台，再做其它事；抢不到就自己收摊，别等着被系统杀。
        val isExplicitForeground = intent?.getBooleanExtra(EXTRA_START_FOREGROUND, false) == true
        if (isExplicitForeground && !ensureForegroundStarted()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val result = super.onStartCommand(intent, flags, startId)
        try {
            mediaSession?.let { session ->
                if (PlaybackManager.shouldHoldForeground()) {
                    onUpdateNotification(session, true)
                }
            }
        } catch (t: Throwable) {
            Log.w("PlaybackService", "Failed to refresh notification on start", t)
            CrashGuard.record("PlaybackService.onStartCommand", t)
        }
        return result
    }

    /** @return 是否成功进入前台。失败时不抛，由调用方决定收敛方式。 */
    private fun ensureForegroundStarted(): Boolean =
        try {
            val notification = createForegroundPlaceholderNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (t: Throwable) {
            Log.w("PlaybackService", "Failed to startForeground: $t")
            CrashGuard.record("PlaybackService.startForeground", t)
            false
        }

    private fun createForegroundPlaceholderNotification(): Notification {
        setupNotificationChannel()
        val currentSong = PlaybackManager.currentSong.value
        val title = currentSong?.name?.ifBlank { null } ?: "音乐播放服务"
        val artist = currentSong?.singer?.ifBlank { null } ?: "正在准备播放..."

        val launchIntent =
            packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(Intent.ACTION_MAIN).apply {
                    setPackage(packageName)
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
        launchIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val sessionActivity =
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(org.melodist.core.playback.R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(sessionActivity)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ) {
        val shouldHold = PlaybackManager.shouldHoldForeground()
        super.onUpdateNotification(session, startInForegroundRequired || shouldHold)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        PlaybackManager.flushActivePlaybackSession()
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        PlaybackManager.legacyMetadataSyncCallback = null
        PlaybackManager.flushActivePlaybackSession()
        updateWakeLock(false)
        serviceScope.cancel()
        forwardingPlayer = null
        PlaybackManager.onActivePlayerSwappedListener = null
        mediaSession?.run {
            removeSession(this)
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    private fun syncLegacyMediaMetadata(
        song: org.melodist.model.Song,
        lyrics: List<org.melodist.model.LyricLine>,
    ) {
        val session = mediaSession ?: return
        try {
            val sessionCompat = getSessionCompat(session) ?: return
            val fwkSession = sessionCompat.mediaSession as? android.media.session.MediaSession ?: return
            val builder = android.media.MediaMetadata.Builder()
            builder.putString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID, song.songMid)
            builder.putString(android.media.MediaMetadata.METADATA_KEY_TITLE, song.name)
            builder.putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, song.singer)
            builder.putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, song.album)
            val duration =
                PlaybackManager.durationMs.value.takeIf { it > 0 }
                    ?: (if (song.durationSeconds > 0) song.durationSeconds * 1000L else 0L)
            if (duration > 0) {
                builder.putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, duration)
            }
            val currentFwk = fwkSession.controller.metadata
            if (currentFwk != null && currentFwk.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID) == song.songMid) {
                val existingArt =
                    currentFwk.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: currentFwk.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)
                if (existingArt != null) {
                    builder.putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, existingArt)
                    builder.putBitmap(android.media.MediaMetadata.METADATA_KEY_ART, existingArt)
                }
            }
            val extras = PlaybackMediaItemFactory.extraMetadataProvider?.invoke(song, lyrics)
            val hasLyricInfo = extras?.containsKey("lyricInfo") == true
            if (extras != null && !extras.isEmpty) {
                for (key in extras.keySet()) {
                    @Suppress("DEPRECATION")
                    val value = extras.get(key)
                    if (value is CharSequence) {
                        builder.putString(key, value.toString())
                    } else if (value is Number) {
                        builder.putLong(key, value.toLong())
                    }
                }
            }
            if (!hasLyricInfo && currentFwk != null && currentFwk.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID) == song.songMid) {
                val existingLyricInfo = currentFwk.getString("lyricInfo")
                val existingConfig = currentFwk.getString("oplus.media.config")
                if (!existingLyricInfo.isNullOrBlank()) {
                    builder.putString("lyricInfo", existingLyricInfo)
                }
                if (!existingConfig.isNullOrBlank()) {
                    builder.putString("oplus.media.config", existingConfig)
                }
            }
            fwkSession.setMetadata(builder.build())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("PlaybackService", "Failed to sync legacy MediaMetadata: $e")
        }
    }

    private fun getSessionCompat(session: MediaSession): androidx.media3.session.legacy.MediaSessionCompat? =
        runCatching {
            val method = session.javaClass.getDeclaredMethod("getSessionCompat")
            method.isAccessible = true
            method.invoke(session) as? androidx.media3.session.legacy.MediaSessionCompat
        }.recoverCatching {
            val implField = session.javaClass.getDeclaredField("impl")
            implField.isAccessible = true
            val impl = implField.get(session)
            val method = impl.javaClass.getDeclaredMethod("getSessionCompat")
            method.isAccessible = true
            method.invoke(impl) as? androidx.media3.session.legacy.MediaSessionCompat
        }.getOrNull()

    private fun updateWakeLock(acquire: Boolean) {
        try {
            if (acquire) {
                if (wakeLock?.isHeld == false) {
                    wakeLock?.acquire(10 * 60 * 1000L)
                }
            } else {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("PlaybackService", "Error toggling wake lock", e)
        }
    }

    private fun isCurrentSongFavorite(): Boolean {
        val song = PlaybackManager.currentSong.value ?: return false
        return PlaybackManager.favoriteSongMids.value.contains(song.songMid)
    }

    private fun createFavoriteButton(isFavorite: Boolean): CommandButton {
        val icon = if (isFavorite) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED
        val displayName = if (isFavorite) "取消收藏" else "收藏"
        return CommandButton
            .Builder(icon)
            .setSessionCommand(SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY))
            .setDisplayName(displayName)
            .setEnabled(true)
            .build()
    }

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            try {
                // 清理旧版本（Melodist）遗留的通知渠道，避免升级后残留失效渠道
                manager?.deleteNotificationChannel("melodist_playback_channel")
                manager?.deleteNotificationChannel("melodist_playback_channel_v2")
                manager?.deleteNotificationChannel("melodist_playback_channel_v3")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("PlaybackService", "Operation failed", e)
            }
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "媒体播放控制",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "显示正在播放的音乐信息与控制控件"
                    setShowBadge(true)
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            manager?.createNotificationChannel(channel)
        }
    }
}
