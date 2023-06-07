package com.pravera.flutter_foreground_task.widget

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView


class TypeFaceTextView : AppCompatTextView {
    constructor(context: Context) : super(context) {
        if (!isInEditMode) {
            setupTextView()
        }
    }

    private fun setupTextView() {
        val typeface: Typeface = Typeface.createFromAsset(context.assets, "iransans_reg.ttf")
        setTypeface(typeface)
    }

    constructor(context: Context, attributeSet: AttributeSet) : super(
        context, attributeSet
    ) {
        if (!isInEditMode) {
            setupTextView()
        }
    }

    constructor(context: Context, attributeSet: AttributeSet, i: Int) : super(
        context, attributeSet, i
    ) {
        if (!isInEditMode) {
            setupTextView()
        }
    }
}