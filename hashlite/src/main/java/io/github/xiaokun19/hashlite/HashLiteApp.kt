package io.github.xiaokun19.hashlite

import android.app.Application

/**
 * 只做一件事：尽早装上诊断日志的全局异常捕获。
 *
 * Application.onCreate 早于一切 Activity / Service / Provider——在这里挂
 * `Thread.setDefaultUncaughtExceptionHandler` 才能覆盖到启动早期的崩溃。
 */
class HashLiteApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Diagnostics.install(this)
    }
}