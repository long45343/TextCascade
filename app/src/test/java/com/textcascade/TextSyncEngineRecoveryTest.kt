package com.textcascade

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextSyncEngineRecoveryTest {

    @Test
    fun rawWebSocketClientErrorPathFiresSingleCallback() {
        val callbacks = RecordingListener()
        // 连接一个必然失败的端口，触发 WebSocket 连接异常
        val client = RawWebSocketClient(
            url = "ws://127.0.0.1:1",
            cookieHeader = "",
            listener = callbacks
        )
        client.connect()

        // 等待异步线程完成错误路径
        awaitCondition { callbacks.closed.isNotEmpty() || callbacks.errors.isNotEmpty() || callbacks.sessionExpired.isNotEmpty() }

        assertTrue("expected onError", callbacks.errors.isNotEmpty())
        assertEquals("onError should be the terminal callback", 1, callbacks.closed.size + callbacks.errors.size + callbacks.sessionExpired.size)
        assertTrue("broken session expired flag", !callbacks.sessionExpired.isNotEmpty())
    }

    @Test
    fun engineCanBeRestartedAfterStop() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = ClipConfig.default(context).copy(
            websocketUrl = "ws://127.0.0.1:1",
            cookieHeader = "missing",
            cipherEnabled = false
        )
        val engine = TextSyncEngine(
            context = context,
            config = config,
            callbacks = object : TextSyncEngine.Callbacks {
                override fun onStatus(message: String) {}
                override fun onRemoteTextApplied(text: String) {}
            }
        )

        engine.stop()
        assertTrue(engine.isStopped)

        // 不应抛 RejectedExecutionException
        engine.start()
        assertFalse(engine.isStopped)
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !condition()) {
            Thread.sleep(20)
        }
    }

    private class RecordingListener : RawWebSocketClient.Listener {
        val closed = mutableListOf<String>()
        val errors = mutableListOf<Throwable>()
        val sessionExpired = mutableListOf<Throwable>()

        override fun onOpen() {}

        override fun onText(text: String) {}

        override fun onClosed(reason: String) {
            closed += reason
        }

        override fun onError(error: Throwable) {
            errors += error
        }

        override fun onSessionExpired(error: SessionExpiredException) {
            sessionExpired += error
        }
    }
}
