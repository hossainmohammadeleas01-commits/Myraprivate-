package com.myra.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class MyraService : Service() {

    companion object {
        const val ACTION_START = "com.myra.START"
        const val ACTION_STOP = "com.myra.STOP"
        const val ACTION_MUTE = "com.myra.MUTE"
        private const val CHANNEL_ID = "myra_voice"
        private const val NOTIF_ID = 1
        private const val WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key="
        private const val RENEW_MS = 540_000L
        private const val KEEPALIVE_MS = 8_000L
        private const val RECONNECT_MS = 3_000L

        @Volatile var running = false
        /** kind: "u" (user), "m" (myra), "s" (status/system) */
        @Volatile var listener: ((String, String) -> Unit)? = null
        val log = CopyOnWriteArrayList<Pair<String, String>>()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var active = false
    @Volatile private var ready = false
    @Volatile private var speaking = false
    @Volatile private var muted = false
    @Volatile private var lastSend = 0L
    private var greeted = false
    private var fails = 0
    private var inBuf = StringBuilder()
    private var outBuf = StringBuilder()

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private val playQueue = LinkedBlockingQueue<ByteArray>()
    private var wake: PowerManager.WakeLock? = null

    private val prefs get() = getSharedPreferences("myra", MODE_PRIVATE)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopAll(); return START_NOT_STICKY }
            ACTION_MUTE -> {
                muted = !muted
                emit("s", if (muted) "Mic muted 🔇" else "Mic on 🎤")
                updateNotification()
            }
            else -> startAll()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- lifecycle

    private fun startAll() {
        if (active) return
        createChannel()
        val n = buildNotification()
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw SecurityException("Mic permission দাও")
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else startForeground(NOTIF_ID, n)
        } catch (e: Exception) {
            emit("s", "Start failed: ${e.message}")
            stopSelf(); return
        }
        if (prefs.getString("api_key", "").isNullOrBlank()) {
            emit("s", "আগে API Key সেভ করো")
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
        }

        active = true; running = true; greeted = false; fails = 0; muted = false
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "myra:voice").apply { acquire() }
        emit("s", "Connecting...")
        startAudio()
        connect()
        handler.postDelayed(keepAlive, KEEPALIVE_MS)
    }

    private fun stopAll() {
        if (!active && !running) return
        active = false; running = false; ready = false; speaking = false
        handler.removeCallbacksAndMessages(null)
        val w = ws; ws = null
        try { w?.close(1000, "bye") } catch (_: Exception) {}
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        record = null; track = null; playQueue.clear()
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
        emit("s", "MYRA stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------------- audio

    private fun startAudio() {
        val minRec = AudioRecord.getMinBufferSize(
            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minRec, 8192)
        )
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
            }
        } catch (_: Exception) {}
        rec.startRecording()
        record = rec

        val minPlay = AudioTrack.getMinBufferSize(
            24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val tr = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(24000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(maxOf(minPlay, 16384))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        tr.play()
        track = tr

        // mic thread
        Thread {
            val buf = ShortArray(1024)
            while (active) {
                val r = record ?: break
                val n = try { r.read(buf, 0, buf.size) } catch (_: Exception) { -1 }
                if (n <= 0) continue
                if (!ready || muted || speaking) continue
                sendPcm(buf, n)
            }
        }.apply { name = "myra-mic"; start() }

        // playback thread
        Thread {
            var lastWrite = 0L
            while (active) {
                val chunk = try { playQueue.poll(100, TimeUnit.MILLISECONDS) } catch (_: Exception) { null }
                if (chunk == null) {
                    if (speaking && System.currentTimeMillis() - lastWrite > 700) speaking = false
                    continue
                }
                speaking = true
                try { track?.write(chunk, 0, chunk.size) } catch (_: Exception) {}
                lastWrite = System.currentTimeMillis()
            }
        }.apply { name = "myra-play"; start() }
    }

    private fun interruptPlayback() {
        playQueue.clear()
        try { track?.pause(); track?.flush(); track?.play() } catch (_: Exception) {}
        speaking = false
    }

    private fun sendPcm(buf: ShortArray, n: Int) {
        val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) bb.putShort(buf[i])
        val b64 = Base64.encodeToString(bb.array(), Base64.NO_WRAP)
        ws?.send("""{"realtimeInput":{"audio":{"mimeType":"audio/pcm;rate=16000","data":"$b64"}}}""")
        lastSend = System.currentTimeMillis()
    }

    // ---------------------------------------------------------------- websocket

    private fun connect() {
        if (!active) return
        ready = false
        val key = prefs.getString("api_key", "") ?: ""
        val req = Request.Builder().url(WS_URL + Uri.encode(key)).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(buildSetup())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket === ws) handleMessage(text)
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (webSocket === ws) handleMessage(bytes.utf8())
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onDisconnected(webSocket, "closed $code $reason")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onDisconnected(webSocket, "${response?.code ?: ""} ${t.message ?: ""}")
            }
        })
    }

    private fun onDisconnected(sock: WebSocket, why: String) {
        if (sock !== ws) return
        ready = false
        if (!active) return
        fails++
        if (fails >= 5) {
            emit("s", "Connection failed: $why — API key / model চেক করো")
            handler.post { stopAll() }
            return
        }
        emit("s", "Reconnecting... ($why)")
        handler.postDelayed({ connect() }, RECONNECT_MS)
    }

    private val renew = Runnable {
        if (active) {
            val old = ws; ws = null
            try { old?.close(1000, "renew") } catch (_: Exception) {}
            connect()
        }
    }

    private val keepAlive = object : Runnable {
        override fun run() {
            if (!active) return
            if (ready && System.currentTimeMillis() - lastSend > KEEPALIVE_MS) {
                sendPcm(ShortArray(1024), 1024)
            }
            handler.postDelayed(this, KEEPALIVE_MS)
        }
    }

    private fun buildSetup(): String {
        val model = prefs.getString("model", "models/gemini-2.5-flash-native-audio-preview-12-2025")
        val voice = prefs.getString("voice", "Aoede")
        val gen = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("temperature", 0.9)
            .put(
                "speechConfig", JSONObject().put(
                    "voiceConfig", JSONObject().put(
                        "prebuiltVoiceConfig", JSONObject().put("voiceName", voice)
                    )
                )
            )
        val setup = JSONObject()
            .put("model", model)
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt())))
            )
            .put("generationConfig", gen)
            .put("outputAudioTranscription", JSONObject())
            .put("inputAudioTranscription", JSONObject())
        return JSONObject().put("setup", setup).toString()
    }

    private fun systemPrompt(): String {
        val name = prefs.getString("name", "Friend")
        val persona = when (prefs.getString("personality", "gf")) {
            "pro" -> "Your name is MYRA. Speak formal English only. Be precise and efficient. No emojis. Max 2 sentences."
            "asst" -> "Your name is MYRA. Speak friendly Hinglish or English. Balanced and helpful. Max 2-3 sentences."
            else -> "Your name is MYRA. Speak Hinglish (Hindi + English mix) naturally. Tone: warm, caring, emotionally expressive. " +
                "Use words like \"tumhara\", \"haan\", \"acha\", \"bilkul\". Max 2-3 sentences per response."
        }
        return "$persona\nCurrent date/time: ${Date()}.\nThe user's name is $name.\n" +
            "You are speaking ALOUD — keep responses natural and conversational.\n" +
            "When the user asks to open an app or call someone, the phone does it automatically; just confirm briefly."
    }

    private fun greeting(): String {
        val n = prefs.getString("name", "Friend")
        return when (prefs.getString("personality", "gf")) {
            "pro" -> "Good day $n. MYRA is online and ready to assist you."
            "asst" -> "Hello $n! Main MYRA hoon. Kaise help karun aapki?"
            else -> "Hey $n! Main aa gayi hoon. Kya help chahiye tumhe?"
        }
    }

    private fun sendText(t: String) {
        val m = JSONObject().put(
            "clientContent", JSONObject()
                .put("turns", JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", t)))
                ))
                .put("turnComplete", true)
        )
        ws?.send(m.toString())
    }

    private fun handleMessage(text: String) {
        val m = try { JSONObject(text) } catch (_: Exception) { return }
        if (m.has("setupComplete")) {
            ready = true; fails = 0
            emit("s", "Listening... 🎤")
            handler.removeCallbacks(renew)
            handler.postDelayed(renew, RENEW_MS)
            if (!greeted) { greeted = true; handler.postDelayed({ sendText(greeting()) }, 400) }
            return
        }
        val sc = m.optJSONObject("serverContent") ?: return
        if (sc.optBoolean("interrupted")) interruptPlayback()
        val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data")
                if (!data.isNullOrEmpty()) {
                    playQueue.offer(Base64.decode(data, Base64.DEFAULT))
                    speaking = true
                }
            }
        }
        sc.optJSONObject("outputTranscription")?.optString("text")?.let { outBuf.append(it) }
        sc.optJSONObject("inputTranscription")?.optString("text")?.let { inBuf.append(it) }
        if (sc.optBoolean("turnComplete")) {
            val u = inBuf.toString().trim(); val a = outBuf.toString().trim()
            inBuf = StringBuilder(); outBuf = StringBuilder()
            if (u.isNotEmpty()) { emit("u", u); handler.post { runCommand(u) } }
            if (a.isNotEmpty()) emit("m", a)
        }
    }

    // ---------------------------------------------------------------- commands

    private fun runCommand(raw: String) {
        val t = raw.lowercase().replace(Regex("[.,!?]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.contains("stop myra") || t.contains("myra band") || t.contains("band ho jao")) {
            stopAll(); return
        }
        val call = Regex("(?:call|phone|dial)\\s+(?:karo\\s+)?(.+)").find(t)
            ?: Regex("(.+?)\\s+ko\\s+(?:call|phone)").find(t)
        if (call != null) {
            val who = call.groupValues[1].replace(Regex("\\b(karo|kar do|please|my)\\b"), "").trim()
            val number = resolveNumber(who)
            if (number != null) {
                if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                    launch(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
                    emit("s", "📞 Calling $who")
                } else emit("s", "CALL permission দাও")
            } else emit("s", "'$who' এর নম্বর পাইনি")
            return
        }
        val open = Regex("(?:open|launch)\\s+(.+)").find(t)
            ?: Regex("(.+?)\\s+(?:kholo|khol do|open karo|chalu karo|chalao)").find(t)
        if (open != null) {
            val app = open.groupValues[1].replace(Regex("\\b(the|app|karo)\\b"), "").trim()
            openApp(app)
        }
    }

    private fun openApp(name: String) {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val hit = pm.queryIntentActivities(main, 0)
            .firstOrNull { it.loadLabel(pm).toString().lowercase().contains(name) }
        val launch = hit?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
        if (launch != null) {
            launch(launch)
            emit("s", "📱 Opening $name")
        } else emit("s", "'$name' অ্যাপ পাইনি")
    }

    private fun launch(i: Intent) {
        try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i) }
        catch (e: Exception) { emit("s", "Launch failed: ${e.message} (Display over other apps অন করো)") }
    }

    private fun resolveNumber(who: String): String? {
        val digits = who.replace(Regex("[^\\d+]"), "")
        if (digits.length >= 6) return digits
        // prime contacts: lines like "Name:+8801..."
        prefs.getString("prime", "")?.lines()?.forEach { line ->
            val p = line.split(":", limit = 2)
            if (p.size == 2 && p[0].trim().isNotEmpty() &&
                (who.contains(p[0].trim().lowercase()) || p[0].trim().lowercase().contains(who))
            ) return p[1].trim()
        }
        if (Regex("close friend|jaan|love|prime").containsMatchIn(who)) {
            prefs.getString("prime", "")?.lines()?.firstOrNull { it.contains(":") }
                ?.split(":", limit = 2)?.get(1)?.trim()?.let { return it }
        }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$who%"), null
            )?.use { if (it.moveToFirst()) return it.getString(0) }
        } catch (_: Exception) {}
        return null
    }

    // ---------------------------------------------------------------- notification / events

    private fun emit(kind: String, text: String) {
        log.add(kind to text)
        while (log.size > 200) log.removeAt(0)
        listener?.invoke(kind, text)
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "MYRA voice", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        fun act(a: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, MyraService::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MYRA")
            .setContentText(if (muted) "Muted" else "Listening in background")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_lock_silent_mode),
                if (muted) "Unmute" else "Mute", act(ACTION_MUTE, 1)).build())
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "Stop", act(ACTION_STOP, 2)).build())
            .build()
    }

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification())
    }
}
