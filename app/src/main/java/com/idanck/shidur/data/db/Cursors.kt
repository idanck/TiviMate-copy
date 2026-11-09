package com.idanck.shidur.data.db

import android.database.Cursor

internal fun Cursor.str(name: String): String = getString(getColumnIndexOrThrow(name)) ?: ""
internal fun Cursor.strOrNull(name: String): String? {
    val i = getColumnIndexOrThrow(name)
    return if (isNull(i)) null else getString(i)
}
internal fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
internal fun Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))
internal fun Cursor.intOrNull(name: String): Int? {
    val i = getColumnIndexOrThrow(name)
    return if (isNull(i)) null else getInt(i)
}
internal fun Cursor.bool(name: String): Boolean = getInt(getColumnIndexOrThrow(name)) != 0

internal inline fun <T> Cursor.mapAll(body: (Cursor) -> T): List<T> = use { c ->
    val out = ArrayList<T>(c.count.coerceAtMost(4096))
    while (c.moveToNext()) out.add(body(c))
    out
}

internal inline fun <T> Cursor.firstOrNull(body: (Cursor) -> T): T? = use { c ->
    if (c.moveToFirst()) body(c) else null
}
