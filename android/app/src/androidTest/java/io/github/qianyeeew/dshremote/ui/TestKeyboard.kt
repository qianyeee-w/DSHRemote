package io.github.qianyeeew.dshremote.ui

import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** 键盘收起的动画一般三百毫秒左右，留足余量。 */
private const val KEYBOARD_TIMEOUT_MILLIS = 5_000L

/**
 * 真机上点输入框、输入文字都会弹出真的键盘，键盘在的时候输入框下面的「模型 · 审批方式」按设计收起。
 * 这里模拟用户先收起键盘（例如按返回键收起键盘），等键盘完全收起、界面跟着排好再继续。
 * [view] 是被测界面所在的 View（在 setContent 里取 LocalView.current）。
 */
internal fun ComposeContentTestRule.hideKeyboard(view: View) {
    runOnUiThread {
        view.context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
    }
    waitUntil(KEYBOARD_TIMEOUT_MILLIS) {
        runOnUiThread { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) != true }
    }
    waitForIdle()
}
