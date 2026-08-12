/*
 * TextCascade Android - Native clipboard sync client for ClipCascade
 * Copyright (C) 2026  Manet Kirby
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.textcascade

import android.graphics.Color
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PasswordHintTest {

    private fun buildActivity(): MainActivity {
        return Robolectric.buildActivity(MainActivity::class.java).setup().get()
    }

    private fun form(activity: MainActivity): LinearLayout {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val scroll = content.getChildAt(0) as ViewGroup
        return scroll.getChildAt(0) as LinearLayout
    }

    private fun passwordInput(form: LinearLayout): EditText {
        for (i in 0 until form.childCount) {
            val child = form.getChildAt(i)
            if (child is EditText &&
                child.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0
            ) {
                return child
            }
        }
        error("password input not found")
    }

    private fun passwordIndicator(form: LinearLayout): TextView {
        val passwordIndex = form.indexOfChild(passwordInput(form))
        return form.getChildAt(passwordIndex + 1) as TextView
    }

    private fun savePasswordCheck(activity: MainActivity, form: LinearLayout): CheckBox {
        val label = activity.getString(R.string.option_save_password)
        for (i in 0 until form.childCount) {
            val child = form.getChildAt(i)
            if (child is CheckBox && child.text.toString() == label) {
                return child
            }
        }
        error("save password checkbox not found")
    }

    @Test
    fun indicatorHiddenWithoutSavedPassword() {
        val activity = buildActivity()
        val form = form(activity)
        val indicator = passwordIndicator(form)
        assertEquals(View.GONE, indicator.visibility)
    }

    @Test
    fun indicatorVisibleAndVersionedTitleAfterSavedPassword() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SettingsStore(context).apply {
            savePassword = true
            savedEncryptedPassword = "saved-password"
        }

        val activity = buildActivity()
        val form = form(activity)
        val indicator = passwordIndicator(form)
        assertEquals(View.VISIBLE, indicator.visibility)
        assertTrue(indicator.text.toString().contains("saved") || indicator.text.toString().contains("已保存"))
        assertEquals(Color.parseColor("#2E7D32"), indicator.currentTextColor)

        val title = form.getChildAt(0) as TextView
        assertTrue(title.text.toString().startsWith("TextCascade v"))
    }

    @Test
    fun checkboxTogglesIndicatorImmediately() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SettingsStore(context).apply {
            savePassword = true
            savedEncryptedPassword = "saved-password"
        }

        val activity = buildActivity()
        val form = form(activity)
        val check = savePasswordCheck(activity, form)
        val indicator = passwordIndicator(form)

        check.isChecked = false
        assertEquals(View.GONE, indicator.visibility)

        check.isChecked = true
        assertEquals(View.VISIBLE, indicator.visibility)
    }
}
