package com.myra.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var chat: TextView
    private lateinit var toggle: Button
    private lateinit var key: EditText
    private lateinit var name: EditText
    private lateinit var prime: EditText
    private lateinit var modelSp: Spinner
    private lateinit var voiceSp: Spinner
    private lateinit var persSp: Spinner

    private val models = listOf(
        "models/gemini-2.5-flash-native-audio-preview-12-2025",
        "models/gemini-2.0-flash-live-001",
        "models/gemini-2.5-flash-preview-native-audio-dialog"
    )
    private val voices = listOf("Aoede", "Charon", "Kore", "Fenrir", "Puck", "Leda", "Orus", "Zephyr")
    private val pers = listOf("gf", "pro", "asst")
    private val persLabels = listOf("GF (Hinglish)", "Professional", "Assistant")

    private val RED = Color.parseColor("#FF1744")
    private val BG = Color.parseColor("#050505")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(t: String) = TextView(this).apply {
        text = t; setTextColor(Color.parseColor("#888888")); textSize = 11f
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun edit(hint: String, pwd: Boolean = false) = EditText(this).apply {
        this.hint = hint; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        setBackgroundColor(Color.parseColor("#151515")); setPadding(dp(12), dp(10), dp(12), dp(10))
        if (pwd) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun spinner(items: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setBackgroundColor(Color.parseColor("#151515"))
    }

    private fun button(t: String, click: () -> Unit) = Button(this).apply {
        text = t; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#1f1f1f"))
        setOnClickListener { click() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("myra", MODE_PRIVATE)
        window.statusBarColor = BG; window.navigationBarColor = BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(36), dp(18), dp(24))
        }
        val title = TextView(this).apply {
            text = "MYRA"; setTextColor(RED); textSize = 30f; gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD; letterSpacing = 0.2f
        }
        val sub = TextView(this).apply {
            text = "YOUR VOICE. YOUR COMMAND."; setTextColor(Color.GRAY); textSize = 11f; gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "Stopped"; setTextColor(Color.LTGRAY); textSize = 14f; gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(10))
        }
        toggle = Button(this).apply {
            textSize = 16f; setTextColor(Color.WHITE); setBackgroundColor(RED)
            setOnClickListener { onToggle() }
        }
        chat = TextView(this).apply {
            setTextColor(Color.parseColor("#DDDDDD")); textSize = 14f
            setPadding(dp(4), dp(14), dp(4), dp(8))
        }

        key = edit("Gemini API Key", true)
        name = edit("Your name")
        prime = edit("Prime contacts (প্রতি লাইনে Name:Number)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        modelSp = spinner(listOf("Native Audio (Human Voice)", "Flash Live (Fast)", "Pro Audio Dialog"))
        voiceSp = spinner(voices)
        persSp = spinner(persLabels)

        key.setText(prefs.getString("api_key", ""))
        name.setText(prefs.getString("name", ""))
        prime.setText(prefs.getString("prime", ""))
        modelSp.setSelection(models.indexOf(prefs.getString("model", models[0])).coerceAtLeast(0))
        voiceSp.setSelection(voices.indexOf(prefs.getString("voice", "Aoede")).coerceAtLeast(0))
        persSp.setSelection(pers.indexOf(prefs.getString("personality", "gf")).coerceAtLeast(0))

        root.addView(title); root.addView(sub); root.addView(status)
        root.addView(toggle, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(chat)
        root.addView(label("SETTINGS"))
        root.addView(label("API KEY")); root.addView(key)
        root.addView(label("YOUR NAME")); root.addView(name)
        root.addView(label("AI MODEL")); root.addView(modelSp)
        root.addView(label("VOICE")); root.addView(voiceSp)
        root.addView(label("PERSONALITY")); root.addView(persSp)
        root.addView(label("PRIME CONTACTS")); root.addView(prime)
        root.addView(button("SAVE") { save() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        root.addView(label("PERMISSIONS (ব্যাকগ্রাউন্ডে ভালো চলার জন্য)"))
        root.addView(button("Display over other apps (অ্যাপ খোলার জন্য)") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(button("Battery: Unrestricted করো") {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } else Toast.makeText(this, "আগে থেকেই ঠিক আছে ✓", Toast.LENGTH_SHORT).show()
        })

        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(root) })
    }

    override fun onResume() {
        super.onResume()
        chat.text = ""
        MyraService.log.forEach { show(it.first, it.second) }
        MyraService.listener = { k, t -> runOnUiThread { show(k, t) } }
        refreshToggle()
    }

    override fun onPause() {
        MyraService.listener = null
        super.onPause()
    }

    private fun show(kind: String, text: String) {
        when (kind) {
            "s" -> status.text = text
            "u" -> chat.append("🗣 $text\n\n")
            "m" -> chat.append("❤ $text\n\n")
        }
        refreshToggle()
    }

    private fun refreshToggle() {
        toggle.text = if (MyraService.running) "STOP MYRA" else "START MYRA"
        if (!MyraService.running && status.text.toString().let { it.startsWith("Listening") || it.startsWith("Connecting") })
            status.text = "Stopped"
    }

    private fun save() {
        prefs.edit()
            .putString("api_key", key.text.toString().trim())
            .putString("name", name.text.toString().trim().ifEmpty { "Friend" })
            .putString("prime", prime.text.toString().trim())
            .putString("model", models[modelSp.selectedItemPosition])
            .putString("voice", voices[voiceSp.selectedItemPosition])
            .putString("personality", pers[persSp.selectedItemPosition])
            .apply()
        Toast.makeText(this, "Saved ✓ (চালু থাকলে Stop → Start করো)", Toast.LENGTH_SHORT).show()
    }

    private fun onToggle() {
        if (MyraService.running) {
            startService(Intent(this, MyraService::class.java).setAction(MyraService.ACTION_STOP))
            return
        }
        save()
        val need = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else startMyra()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startMyra()
        } else Toast.makeText(this, "Mic permission ছাড়া চলবে না", Toast.LENGTH_LONG).show()
    }

    private fun startMyra() {
        startForegroundService(Intent(this, MyraService::class.java).setAction(MyraService.ACTION_START))
        status.text = "Connecting..."
        toggle.text = "STOP MYRA"
    }
}
