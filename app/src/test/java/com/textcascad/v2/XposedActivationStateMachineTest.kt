/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import io.github.libxposed.service.XposedService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit

/**
 * 10.1 激活状态机测试：
 * 覆盖：
 * - 初始状态为 DETECTING。
 * - service 未绑定时保持 DETECTING。
 * - API < 102 时保持 DETECTING。
 * - getRunningTargets() 抛异常时保持 DETECTING。
 * - 第一次非空目标列表后进入 ACTIVE 并锁存。
 * - 5 秒超时仍未非空时进入 INACTIVE。
 * - 已 ACTIVE 后 service 死亡仍保持 ACTIVE。
 * - 进程重启后重置为 DETECTING。
 */
@RunWith(RobolectricTestRunner::class)
class XposedActivationStateMachineTest {

    private lateinit var app: TextCascadeApplication

    @Before
    fun setUp() {
        TextCascadeApplication.resetForTest()
        app = RuntimeEnvironment.getApplication() as TextCascadeApplication
    }

    @After
    fun tearDown() {
        TextCascadeApplication.resetForTest()
    }

    private fun createFakeService(
        apiVersion: Int = 102,
        hasTargets: Boolean = false,
        throwOnTargets: Boolean = false
    ): XposedService {
        val iInterfaceClass = Class.forName("io.github.libxposed.service.IXposedService")
        val hookedProcessClass = Class.forName("io.github.libxposed.service.HookedProcess")

        val targetList = if (hasTargets) {
            val process = hookedProcessClass.getDeclaredConstructor().newInstance()
            hookedProcessClass.getField("pid").setInt(process, 1234)
            hookedProcessClass.getField("uid").setInt(process, 1000)
            hookedProcessClass.getField("processName").set(process, "system_server")
            hookedProcessClass.getField("state").setInt(process, 0)
            hookedProcessClass.getField("loadedVersionCode").setLong(process, 18L)
            listOf(process)
        } else {
            emptyList<Any>()
        }

        val handler = InvocationHandler { _, method, args ->
            when (method.name) {
                "getApiVersion" -> apiVersion
                "getRunningTargets" -> {
                    if (throwOnTargets) throw RuntimeException("Remote binder error")
                    targetList
                }
                "toString" -> "FakeXposedService(api=$apiVersion)"
                else -> null
            }
        }
        val iProxy = Proxy.newProxyInstance(iInterfaceClass.classLoader, arrayOf(iInterfaceClass), handler)
        val ctor = XposedService::class.java.getDeclaredConstructor(iInterfaceClass)
        ctor.isAccessible = true
        return ctor.newInstance(iProxy)
    }

    @Test
    fun initialStateIsDetecting() {
        assertEquals(XposedActivationState.DETECTING, TextCascadeApplication.activationState)
        assertNull(TextCascadeApplication.currentService)
    }

    @Test
    fun remainsDetectingWhenServiceNullOrUnbound() {
        app.refreshActivationIfNeeded()
        assertEquals(XposedActivationState.DETECTING, TextCascadeApplication.activationState)
    }

    @Test
    fun remainsDetectingWhenApiVersionLowerThan102() {
        val fakeService = createFakeService(apiVersion = 101, hasTargets = true)
        app.onServiceBind(fakeService)
        assertEquals(XposedActivationState.DETECTING, TextCascadeApplication.activationState)
    }

    @Test
    fun remainsDetectingWhenGetRunningTargetsThrows() {
        val fakeService = createFakeService(apiVersion = 102, throwOnTargets = true)
        app.onServiceBind(fakeService)
        assertEquals(XposedActivationState.DETECTING, TextCascadeApplication.activationState)
    }

    @Test
    fun firstNonEmptyRunningTargetsTransitionsToActiveAndLatches() {
        val fakeService = createFakeService(apiVersion = 102, hasTargets = true)
        app.onServiceBind(fakeService)
        assertEquals(XposedActivationState.ACTIVE, TextCascadeApplication.activationState)

        // 即使服务死亡，已锁存的 ACTIVE 不会退回到 DETECTING 或 INACTIVE
        app.onServiceDied(fakeService)
        assertEquals(XposedActivationState.ACTIVE, TextCascadeApplication.activationState)

        // 再次收到空列表或低版本服务，依然锁存 ACTIVE
        val emptyService = createFakeService(apiVersion = 102, hasTargets = false)
        app.onServiceBind(emptyService)
        assertEquals(XposedActivationState.ACTIVE, TextCascadeApplication.activationState)
    }

    @Test
    fun timesOutToInactiveAfter5SecondsIfTargetsRemainEmpty() {
        val fakeService = createFakeService(apiVersion = 102, hasTargets = false)
        app.onServiceBind(fakeService)
        assertEquals(XposedActivationState.DETECTING, TextCascadeApplication.activationState)

        // 推进 4 秒，依然是 DETECTING
        ShadowLooper.idleMainLooper(4000, TimeUnit.MILLISECONDS)
        assertEquals(XposedActivationState.DETECTING, TextCascadeApplication.activationState)

        // 推进到 5 秒，变为 INACTIVE
        ShadowLooper.idleMainLooper(1000, TimeUnit.MILLISECONDS)
        assertEquals(XposedActivationState.INACTIVE, TextCascadeApplication.activationState)
    }

    @Test
    fun serviceDiedBeforeActiveResetsToDetecting() {
        val fakeService = createFakeService(apiVersion = 102, hasTargets = false)
        app.onServiceBind(fakeService)
        assertEquals(XposedActivationState.DETECTING, TextCascadeApplication.activationState)

        // 5秒未到，服务挂掉
        app.onServiceDied(fakeService)
        assertEquals(XposedActivationState.DETECTING, TextCascadeApplication.activationState)
        assertNull(TextCascadeApplication.currentService)

        // 之后即使5秒计时器触发，由于已重置，不会有脏状态
        ShadowLooper.idleMainLooper(5000, TimeUnit.MILLISECONDS)
        assertEquals(XposedActivationState.DETECTING, TextCascadeApplication.activationState)
    }

    @Test
    fun resetForTestSimulatesProcessRestart() {
        val fakeService = createFakeService(apiVersion = 102, hasTargets = true)
        app.onServiceBind(fakeService)
        assertEquals(XposedActivationState.ACTIVE, TextCascadeApplication.activationState)

        // 模拟进程重启
        TextCascadeApplication.resetForTest()
        assertEquals(XposedActivationState.DETECTING, TextCascadeApplication.activationState)
        assertNull(TextCascadeApplication.currentService)
    }
}