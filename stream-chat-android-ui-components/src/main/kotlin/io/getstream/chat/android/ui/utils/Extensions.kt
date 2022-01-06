package io.getstream.chat.android.ui.utils

import android.content.Context
import android.view.View

public val Context.isRtlLayout: Boolean
    get() = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
