package com.idanck.shidur.ui.common

import android.content.Context
import com.idanck.shidur.R
import com.idanck.shidur.core.AppError

/**
 * Turns an [AppError] into the plain-language sentence NFR-REL-06 requires. Every branch names
 * what failed and, where there is one, what the user can do about it.
 */
fun AppError.describe(context: Context): String = when (kind) {
    AppError.Kind.Network -> context.getString(R.string.err_network)
    AppError.Kind.Timeout -> context.getString(R.string.err_timeout)
    AppError.Kind.Unauthorized -> context.getString(R.string.err_unauthorized)
    AppError.Kind.Forbidden -> context.getString(R.string.err_forbidden)
    AppError.Kind.NotFound -> context.getString(R.string.err_not_found)
    AppError.Kind.EmptyResponse -> context.getString(R.string.err_empty)
    AppError.Kind.ParseFailed -> context.getString(R.string.err_parse)
    AppError.Kind.UnsupportedFormat -> context.getString(R.string.err_unsupported)
    AppError.Kind.GeoBlocked -> context.getString(R.string.err_geo)
    AppError.Kind.DrmUnsupported -> context.getString(R.string.err_drm)
    AppError.Kind.DecoderFailed -> context.getString(R.string.err_decoder)
    AppError.Kind.OutsideCatchupWindow -> context.getString(R.string.err_catchup_window)
    AppError.Kind.CatchupUnsupported -> context.getString(R.string.err_catchup_unsupported)
    AppError.Kind.InsufficientStorage -> context.getString(R.string.err_storage)
    AppError.Kind.PermissionDenied -> context.getString(R.string.err_permission)
    AppError.Kind.Cancelled -> context.getString(R.string.err_cancelled)
    AppError.Kind.SignatureMismatch -> context.getString(R.string.err_signature_mismatch)
    AppError.Kind.Unknown -> context.getString(R.string.err_unknown, detail ?: "—")
}
