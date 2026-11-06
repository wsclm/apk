package com.cpsfloat

import android.app.*
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.*
import android.widget.TextView
import java.util.concurrent.ConcurrentLinkedQueue

class FloatService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var sp: SharedPreferences
    private val h = Handler(Looper.getMainLooper())
    private val times = ConcurrentLinkedQueue<Long>()
    private var tv: TextView? = null
    private var watcher: View? = null
    private var area: View? = null
    private var proc: Process? = null
    @Volatile private var alive = true

    override fun onBind(i: Intent?): IBinder? = null

    private fun int(k: String, d: Int) = sp.getString(k, null)?.toIntOrNull() ?: d
    private fun color(k: String, d: String) = try { Color.parseColor(sp.getString(k, d)) } catch (e: Exception) { Color.parseColor(d) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun hit() { times.add(System.currentTimeMillis()) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sp = getSharedPreferences("cfg", 0)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("cps", "CPS", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, "cps").setContentTitle("CPS悬浮窗运行中")
            .setSmallIcon(android.R.drawable.ic_menu_compass).build())
        clear(); build()
        return START_STICKY
    }

    private fun lp(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }

    private fun build() {
        val tpl = sp.getString("tpl", "CPS: {cps}")!!
        val onlyBgIdle = sp.getBoolean("idleBg", false)
        val v = TextView(this).apply {
            setTextColor(color("fg", "#FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, int("size", 18).toFloat())
            setPadding(dp(14), dp(8), dp(14), dp(8))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(color("bg", "#99000000")); cornerRadius = dp(int("radius", 12)).toFloat() }
            minWidth = (paint.measureText(tpl.replace("{cps}", "00")) + dp(28)).toInt()
        }
        tv = v
        wm.addView(v, lp(-2, -2, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            .apply { x = int("px", 50); y = int("py", 200) })

        when (int("mode", 0)) {
            0 -> { // 无需 Shizuku：用 ACTION_OUTSIDE 统计全屏点击
                val w = View(this)
                w.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) hit(); false }
                wm.addView(w, lp(1, 1, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH))
                watcher = w
            }
            1 -> startShizukuListener()
            2 -> { // 区域覆盖：该区域点击会被本软件接收（不会传给下层）
                val a = View(this).apply { setBackgroundColor(Color.parseColor("#22FFFFFF")) }
                a.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) hit(); true }
                wm.addView(a, lp(int("rw", 400), int("rh", 400), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS).apply { x = int("rx", 100); y = int("ry", 400) })
                area = a
            }
        }

        h.post(object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                while (times.peek()?.let { now - it > 1000 } == true) times.poll()
                val n = times.size
                tv?.text = if (n == 0 && onlyBgIdle) "" else tpl.replace("{cps}", n.toString())
                h.postDelayed(this, 80)
            }
        })
    }

    /** Shizuku 模式：读取原始触摸事件，只统计落在指定区域内的点击 */
    private fun startShizukuListener() {
        if (!Sh.ready()) return
        Thread {
            try {
                var mx = 0f; var my = 0f
                Sh.run("getevent -lp").inputStream.bufferedReader().forEachLine { l ->
                    Regex("ABS_MT_POSITION_([XY]).*max (\\d+)").find(l)?.let {
                        val m = it.groupValues[2].toFloat()
                        if (it.groupValues[1] == "X" && mx == 0f) mx = m
                        if (it.groupValues[1] == "Y" && my == 0f) my = m
                    }
                }
                val dm = DisplayMetrics(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
                val rx = int("rx", 100); val ry = int("ry", 400); val rw = int("rw", 400); val rh = int("rh", 400)
                var cx = 0L; var cy = 0L; var pend = false
                proc = Sh.run("getevent -l")
                proc!!.inputStream.bufferedReader().forEachLine { l ->
                    if (!alive) return@forEachLine
                    val t = l.trim().split(Regex("\\s+"))
                    if (t.size < 4) return@forEachLine
                    when (t[2]) {
                        "ABS_MT_TRACKING_ID" -> if (t[3] != "ffffffff") pend = true
                        "BTN_TOUCH" -> if (t[3] == "DOWN") pend = true
                        "ABS_MT_POSITION_X" -> cx = (t[3].toLong(16) * dm.widthPixels / maxOf(mx, 1f)).toLong()
                        "ABS_MT_POSITION_Y" -> cy = (t[3].toLong(16) * dm.heightPixels / maxOf(my, 1f)).toLong()
                        "SYN_REPORT" -> if (pend) {
                            pend = false
                            if (cx in rx..(rx + rw) && cy in ry..(ry + rh)) hit()
                        }
                    }
                }
            } catch (e: Throwable) { e.printStackTrace() }
        }.start()
    }

    private fun clear() {
        h.removeCallbacksAndMessages(null)
        listOfNotNull(tv, watcher, area).forEach { try { wm.removeView(it) } catch (_: Exception) {} }
        tv = null; watcher = null; area = null
    }

    override fun onDestroy() { alive = false; clear(); proc?.destroy(); super.onDestroy() }
}
