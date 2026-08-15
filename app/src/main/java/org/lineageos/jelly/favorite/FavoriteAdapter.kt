/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.favorite

import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.lineageos.jelly.R
import org.lineageos.jelly.model.Favorite
import org.lineageos.jelly.utils.UiUtils

class FavoriteAdapter : ListAdapter<Favorite, FavoriteAdapter.FavoriteHolder>(diffCallback) {
    var onCardClick: ((Favorite) -> Unit) = { }
    var onCardLongClick: ((Favorite) -> Unit) = { }

    override fun onCreateViewHolder(parent: ViewGroup, type: Int) = FavoriteHolder(
        LayoutInflater.from(parent.context)
            .inflate(R.layout.item_favorite, parent, false)
    )

    override fun onBindViewHolder(holder: FavoriteHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FavoriteHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val card = view.findViewById<View>(R.id.rowFavoriteCard)
        private val banner = view.findViewById<View>(R.id.rowFavoriteBanner)
        private val initial = view.findViewById<TextView>(R.id.rowFavoriteInitial)
        private val favicon = view.findViewById<ImageView>(R.id.rowFavoriteFavicon)
        private val title = view.findViewById<TextView>(R.id.rowFavoriteTitle)
        private val host = view.findViewById<TextView>(R.id.rowFavoriteHost)

        fun bind(favorite: Favorite) {
            val hostText = favorite.url.split("/").getOrNull(2) ?: favorite.url
            val displayTitle = favorite.title.takeUnless { it.isEmpty() } ?: hostText

            title.text = displayTitle
            host.text = hostText

            // Banner takes the stored color; the circle + text adapt to its
            // luminance (white circle on dark banners, dark on light ones).
            banner.setBackgroundColor(favorite.color)
            val light = UiUtils.isColorLight(favorite.color)
            initial.setBackgroundColor(
                if (light) Color.argb(60, 0, 0, 0) else Color.argb(60, 255, 255, 255)
            )
            initial.setTextColor(if (light) Color.BLACK else Color.WHITE)
            initial.text = (displayTitle.firstOrNull() ?: '?').uppercaseChar().toString()

            // The site's web icon when known; the initial otherwise.
            val bytes = favorite.favicon
            val bmp = bytes?.let { runCatching {
                BitmapFactory.decodeByteArray(it, 0, it.size)
            }.getOrNull() }
            if (bmp != null) {
                favicon.setImageBitmap(bmp)
                favicon.visibility = View.VISIBLE
                initial.visibility = View.GONE
            } else {
                favicon.setImageBitmap(null)
                favicon.visibility = View.GONE
                initial.visibility = View.VISIBLE
            }

            card.setOnClickListener {
                onCardClick(favorite)
            }
            card.setOnLongClickListener {
                onCardLongClick(favorite)
                true
            }
        }
    }

    companion object {
        private val diffCallback = object : DiffUtil.ItemCallback<Favorite>() {
            override fun areItemsTheSame(oldItem: Favorite, newItem: Favorite) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Favorite, newItem: Favorite) =
                oldItem.id == newItem.id && oldItem.title == newItem.title &&
                    oldItem.url == newItem.url && oldItem.color == newItem.color &&
                    (oldItem.favicon?.size ?: 0) == (newItem.favicon?.size ?: 0)
        }
    }
}
