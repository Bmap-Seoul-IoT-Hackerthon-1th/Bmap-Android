package com.app.hackathon.presentation.widget.utils

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

class TopItemDecorator(private val divHeight: Int) : RecyclerView.ItemDecoration() {

    @Override
    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        super.getItemOffsets(outRect, view, parent, state)
        outRect.top = divHeight
    }
}