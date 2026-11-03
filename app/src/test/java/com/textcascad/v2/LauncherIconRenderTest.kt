/*
 * TextCascade Android v2 - Native clipboard sync client
 * Copyright (C) 2026  Manet Kirby
 */

package com.textcascad.v2

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 品牌图标回归：确保自适应图标前景与通知栏小图标能被系统渲染，且构图像素与设计稿一致。
 *
 * 断言只针对可稳定测量的构图属性（包围盒、留白、不透明像素占比），不比对逐像素哈希，
 * 以免不同 Skia 版本之间的抗锯齿差异导致误报。调试时可设置 -Dtextcascade.icon.dump=<dir>
 * 导出实际渲染结果目视核对。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconRenderTest {

    private val app = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun rasterize(resId: Int, size: Int, background: Int? = null): Bitmap {
        val drawable: Drawable = requireNotNull(app.getDrawable(resId)) { "drawable $resId 无法加载" }
        drawable.setBounds(0, 0, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (background != null) canvas.drawColor(background)
        drawable.draw(canvas)
        return bitmap
    }

    private fun inkBounds(bitmap: Bitmap, isDarkInk: Boolean): IntArray {
        var minX = bitmap.width
        var minY = bitmap.height
        var maxX = -1
        var maxY = -1
        var inkPixels = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val c = bitmap.getPixel(x, y)
                val alpha = Color.alpha(c)
                if (alpha < 32) continue
                val dark = Color.red(c) < 128 && Color.green(c) < 128 && Color.blue(c) < 128
                if (dark != isDarkInk) continue
                inkPixels++
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }
        return intArrayOf(minX, minY, maxX, maxY, inkPixels)
    }

    private fun dump(name: String, bitmap: Bitmap) {
        val dir = System.getProperty("textcascade.icon.dump")
            ?: System.getenv("TEXTCASCADE_ICON_DUMP")
            ?: return
        val file = File(dir, "$name.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun adaptiveForegroundGlyphStaysInsideSafeCircle() {
        // 108dp 图层按 432px 光栅化：1dp = 4px；安全圆直径 66dp = 264px，圆心 (216,216)
        val bitmap = rasterize(R.drawable.ic_icon, 432)
        dump("ic_icon", bitmap)

        val bounds = inkBounds(bitmap, isDarkInk = true)
        assertTrue("前景层应绘制出黑色图形", bounds[4] > 0)

        val minX = bounds[0]
        val minY = bounds[1]
        val maxX = bounds[2]
        val maxY = bounds[3]
        val centerX = (minX + maxX) / 2.0
        val centerY = (minY + maxY) / 2.0
        // 图形几何中心应与图层中心重合（设计稿 bbox 中心即画布中心）
        assertTrue("图形水平居中偏移过大: $centerX", Math.abs(centerX - 216.0) <= 4.0)
        assertTrue("图形垂直居中偏移过大: $centerY", Math.abs(centerY - 216.0) <= 4.0)

        // 四角必须留白，否则圆形/圆角裁切会切掉图形本体
        val radius = Math.hypot((maxX - minX) / 2.0, (maxY - minY) / 2.0)
        assertTrue("图形包围盒外接圆半径 $radius 超出安全区 132px", radius <= 132.0)

        // 图形高度：108dp 图层上前景应约 50dp，刚好在启动器放大的可见区里呈现设计稿的 69% 占比
        val glyphHeightDp = (maxY - minY + 1) / 4.0
        assertTrue("图形高度 ${glyphHeightDp}dp 偏离设计值 49.7dp", glyphHeightDp in 44.0..55.0)
    }

    @Test
    fun notificationSmallIconIsSingleColourSilhouette() {
        // 通知栏小图标必须是不透明单色加透明底的剪影，否则系统会渲染成灰色方块
        val bitmap = rasterize(R.mipmap.ic_small_icon, 96)
        dump("ic_small_icon", bitmap)

        var opaqueWhite = 0
        var coloured = 0
        var transparent = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val c = bitmap.getPixel(x, y)
                val alpha = Color.alpha(c)
                when {
                    alpha < 16 -> transparent++
                    // 抗锯齿边缘是半透明白：剪影判定只看实心部分
                    alpha < 240 -> Unit
                    Color.red(c) > 240 && Color.green(c) > 240 && Color.blue(c) > 240 -> opaqueWhite++
                    else -> coloured++
                }
            }
        }
        assertTrue("小图标应包含透明背景", transparent > 0)
        assertTrue("小图标应有白色剪影", opaqueWhite > 0)
        assertEquals("小图标实心部分只允许白色", 0, coloured)

        val bounds = inkBounds(bitmap, isDarkInk = false)
        val glyphHeightDp = (bounds[3] - bounds[1] + 1) / 4.0
        assertTrue("剪影高度 ${glyphHeightDp}dp 未贴合 24dp 视口", glyphHeightDp in 18.0..22.0)
    }

    @Test
    fun adaptiveIconLayersAreWiredForLauncher() {
        val background = requireNotNull(app.getDrawable(R.drawable.ic_launcher_background))
        assertNotNull("背景层缺失", background)
        // 背景保持纯白，使黑色图形在任意遮罩形状下都有足够对比度
        val bitmap = rasterize(R.drawable.ic_launcher_background, 108)
        assertEquals(Color.WHITE, bitmap.getPixel(54, 54))
    }

    @Test
    fun launcherIconRendersThroughAdaptiveIconDrawable() {
        // 系统实际加载的是 @mipmap/ic_launcher 这个 adaptive-icon，而不是单独的前景层；
        // 该用例确保 XML 图层引用（background/foreground/monochrome）真的能解析并合成出图形。
        val bitmap = rasterize(R.mipmap.ic_launcher, 432)
        dump("ic_launcher", bitmap)

        val bounds = inkBounds(bitmap, isDarkInk = true)
        assertTrue("自适应图标应合成出黑色图形", bounds[4] > 0)
        // 启动器遮罩会切掉四角：这里断言遮罩生效且内部是白色背景层
        assertEquals("自适应图标四角应被遮罩裁掉", 0, Color.alpha(bitmap.getPixel(0, 0)))
        assertEquals("自适应图标中心应为白色背景", Color.WHITE, bitmap.getPixel(54, 54))

        // 合成时 AdaptiveIconDrawable 把 108dp 画板放大 1.5 倍再裁到可见区，
        // 因此图形高度约为前景层 49.7dp 的 1.5 倍
        val glyphHeightDp = (bounds[3] - bounds[1] + 1) / 4.0
        assertTrue("合成后图形高度 ${glyphHeightDp}dp 偏离预期 74.5dp", glyphHeightDp in 66.0..82.0)

        // 关键口径：可见区就是 108dp 的 drawable 边界，图形应占约 69%（= 设计稿 glyph/白卡比例）
        val visibleFraction = glyphHeightDp / 108.0
        assertTrue("图形占可见区比例 ${"%.1f".format(visibleFraction * 100)}% 偏离 69%",
            visibleFraction in 0.62..0.76)
    }
}
