package com.kitchenreceipts.app.ui

import android.content.res.Resources
import com.kitchenreceipts.app.R
import com.kitchenreceipts.app.files.UnsupportedFileException

/**
 * What went wrong, said to the operator in their language: a few plain sentences instead of the exception's own
 * (English, technical) message, which stays in the log.
 */
fun friendlyError(res: Resources, e: Throwable): String = when {
    e is UnsupportedFileException && e.text != 0 -> if (e.arg != null) res.getString(e.text, e.arg) else res.getString(e.text)
    e is android.database.sqlite.SQLiteFullException || e.message?.contains("ENOSPC") == true || e.message?.contains("No space left", true) == true ->
        res.getString(R.string.err_storage_full)
    e is android.database.sqlite.SQLiteException -> res.getString(R.string.err_database)
    e is java.io.IOException -> res.getString(R.string.err_file)
    else -> res.getString(R.string.err_generic)
}
