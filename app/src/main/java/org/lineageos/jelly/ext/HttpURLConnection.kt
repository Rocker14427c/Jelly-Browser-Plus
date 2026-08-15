/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.ext

import java.net.HttpURLConnection
import java.nio.charset.Charset
import java.util.Locale

fun HttpURLConnection.getCharset(defaultEncoding: String): Charset = runCatching {
    contentEncoding?.let { return@runCatching Charset.forName(it) }
    // contentType is nullable — a header-less response must not NPE.
    contentType?.split(";")?.map { str ->
        str.trim { it <= ' ' }
    }?.firstOrNull {
        it.lowercase(Locale.US).startsWith("charset=")
    }?.let { Charset.forName(it.substring(8)) }
}.getOrNull() ?: Charset.forName(defaultEncoding)
