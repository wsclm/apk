package com.cpsfloat

import rikka.shizuku.Shizuku

/** 通过 Shizuku 以 shell 身份执行命令 */
object Sh {
    fun ready() = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) { false }

    fun run(cmd: String): Process {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        return m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
    }
}
