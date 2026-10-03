package com.example.utils

import android.content.Context
import com.example.R

object SeasonFormatter {
    fun getSeasonString(context: Context, seasonCount: Int): String {
        return when (seasonCount) {
            1 -> context.getString(R.string.season_one)
            2 -> context.getString(R.string.season_two)
            3 -> context.getString(R.string.season_three)
            4 -> context.getString(R.string.season_four)
            5 -> context.getString(R.string.season_five)
            6 -> context.getString(R.string.season_six)
            7 -> context.getString(R.string.season_seven)
            8 -> context.getString(R.string.season_eight)
            9 -> context.getString(R.string.season_nine)
            10 -> context.getString(R.string.season_ten)
            else -> context.getString(R.string.season_x, seasonCount)
        }
    }
}
