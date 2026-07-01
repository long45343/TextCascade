/*
 * TextCascade Android — Native clipboard sync client for ClipCascade
 * Copyright (C) 2026  Manet Kirby
 *
 * This program is based on ClipCascade
 * Copyright (C) 2024  Sathvik-Rao <https://github.com/Sathvik-Rao/ClipCascade>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.textcascade

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.atomic.AtomicBoolean

class XposedEntry : XposedModule() {
    companion object {
        private const val TAG = "TextCascadeXposed"
        private const val OUR_PACKAGE = "com.textcascade"
    }

    private val installed = AtomicBoolean(false)

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        log(Log.INFO, TAG, "event=module_loaded process=${param.processName} api=$apiVersion framework=$frameworkName version=$frameworkVersion")
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        log(Log.INFO, TAG, "event=system_server_starting")
        try {
            installHooks(param.classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "event=install_failed", t)
        }
    }

    private fun installHooks(classLoader: ClassLoader?) {
        val cl = classLoader ?: return
        if (!installed.compareAndSet(false, true)) {
            log(Log.INFO, TAG, "event=install_skipped reason=already_installed")
            return
        }
        try {
            val clipboardServiceClass = cl.loadClass("com.android.server.clipboard.ClipboardService")
            val method = clipboardServiceClass.getDeclaredMethod("isDefaultIme", Integer.TYPE, String::class.java)
            method.isAccessible = true

            hook(method)
                .setId("textcascade_isDefaultIme")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val args = chain.args
                    if (args.size < 2) {
                        return@intercept chain.proceed()
                    }
                    val packageName = args[1] as? String
                    if (OUR_PACKAGE == packageName) {
                        log(Log.DEBUG, TAG, "event=isDefaultIme_whitelisted package=$packageName")
                        return@intercept true
                    }
                    chain.proceed()
                }

            log(Log.INFO, TAG, "event=hook_registered method=ClipboardService.isDefaultIme")
        } catch (t: NoSuchMethodException) {
            log(Log.WARN, TAG, "event=isDefaultIme_not_found; trying alternate signature")
            try {
                val clipboardServiceClass = cl.loadClass("com.android.server.clipboard.ClipboardService")
                val method = clipboardServiceClass.getDeclaredMethod(
                    "isDefaultIme", Integer.TYPE, String::class.java, Integer.TYPE
                )
                method.isAccessible = true

                hook(method)
                    .setId("textcascade_isDefaultIme2")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val args = chain.args
                        if (args.size < 2) {
                            return@intercept chain.proceed()
                        }
                        val packageName = args[1] as? String
                        if (OUR_PACKAGE == packageName) {
                            log(Log.DEBUG, TAG, "event=isDefaultIme2_whitelisted package=$packageName")
                            return@intercept true
                        }
                        chain.proceed()
                    }
                log(Log.INFO, TAG, "event=hook_registered method=ClipboardService.isDefaultIme(int,String,int)")
            } catch (t2: Throwable) {
                log(Log.ERROR, TAG, "event=install_failed both_signatures", t2)
            }
        }
    }
}
