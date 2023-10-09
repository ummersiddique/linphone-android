/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.ui.main.chat.view

import android.content.Context
import android.text.method.LinkMovementMethod
import android.util.AttributeSet
import android.view.View.MeasureSpec.*
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.ceil

class ChatBubbleTextView : AppCompatTextView {
    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyleAttr: Int
    ) : super(context, attrs, defStyleAttr)

    override fun setText(text: CharSequence?, type: BufferType?) {
        super.setText(text, type)
        // Required for PatternClickableSpan
        movementMethod = LinkMovementMethod.getInstance()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val lines = layout.lineCount
        if (lines > 1 && getMode(widthMeasureSpec) != EXACTLY) {
            val textWidth = (0 until lines).maxOf(layout::getLineWidth)
            val padding = compoundPaddingLeft + compoundPaddingRight
            val w = ceil(textWidth).toInt() + padding

            if (w < measuredWidth) {
                val newWidthMeasureSpec = makeMeasureSpec(w, AT_MOST)
                super.onMeasure(newWidthMeasureSpec, heightMeasureSpec)
            }
        }
    }
}
