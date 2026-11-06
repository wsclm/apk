package com.cpsfloat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private val fields = linkedMapOf(
        "tpl" to ("显示文本（{cps}会替换为数值，例：点击速度 {cps} CPS）" to "CPS: {cps}"),
        "bg" to ("背景色 #AARRGGBB（AA=透明度）" to "#99000000"),
        "fg" to ("文字颜色" to "#FFFFFF"),
        "size" to ("字号 sp" to "18"),
        "radius" to ("圆角 dp" to "12"),
        "px" to ("悬浮窗位置 X(px)" to "50"),
        "py" to ("悬浮窗位置 Y(px)" to "200"),
        "rx" to ("点击区域 左上角X" to "100"),
        "ry" to ("点击区域 左上角Y" to "400"),
        "rw" to ("点击区域 宽" to "400"),
        "rh" to ("点击区域 高" to "400"),
    )
    private val edits = HashMap<String, EditText>()
    private lateinit var idleBg: CheckBox
    private lateinit var mode: RadioGroup
    private val sp by lazy { getSharedPreferences("cfg", 0) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() }; root.addView(this) }

        for ((k, v) in fields) {
            root.addView(TextView(this).apply { text = v.first; textSize = 12f })
            edits[k] = EditText(this).apply { setText(sp.getString(k, v.second)); setSingleLine() }.also { root.addView(it) }
        }
        idleBg = CheckBox(this).apply { text = "未点击时只显示背景（隐藏文字）"; isChecked = sp.getBoolean("idleBg", false) }
        root.addView(idleBg)
        mode = RadioGroup(this).apply {
            listOf("全屏点击统计（无需Shizuku，不能限定区域）", "Shizuku 指定区域统计（不拦截点击）", "区域覆盖统计（拦截该区域点击）")
                .forEachIndexed { i, s -> addView(RadioButton(context).apply { text = s; id = i + 100 }) }
            check(100 + sp.getString("mode", "0")!!.toInt())
        }
        root.addView(mode)

        btn("1. 授予悬浮窗权限（系统设置）") {
            if (Settings.canDrawOverlays(this)) toast("已授权")
            else startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("1'. 系统无法授权？用 Shizuku 授予悬浮窗权限") { withShizuku {
            Thread { try { Sh.run("appops set $packageName SYSTEM_ALERT_WINDOW allow").waitFor()
                runOnUiThread { toast("已通过 Shizuku 授权") } } catch (e: Throwable) { runOnUiThread { toast("失败: $e") } } }.start() } }
        btn("申请 Shizuku 权限") { withShizuku { toast("Shizuku 已授权") } }
        btn("保存并启动") { save(); if (!Settings.canDrawOverlays(this)) toast("请先授予悬浮窗权限")
            else { stopService(Intent(this, FloatService::class.java)); startForegroundService(Intent(this, FloatService::class.java)) } }
        btn("停止") { stopService(Intent(this, FloatService::class.java)) }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun save() = sp.edit().apply {
        edits.forEach { (k, e) -> putString(k, e.text.toString()) }
        putBoolean("idleBg", idleBg.isChecked)
        putString("mode", (mode.checkedRadioButtonId - 100).toString())
    }.apply()

    private fun withShizuku(ok: () -> Unit) {
        try {
            if (!Shizuku.pingBinder()) return toast("请先启动 Shizuku 应用")
            if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) ok()
            else { Shizuku.addRequestPermissionResultListener { _, r -> if (r == 0) ok() }; Shizuku.requestPermission(1) }
        } catch (e: Throwable) { toast("Shizuku 不可用: $e") }
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
