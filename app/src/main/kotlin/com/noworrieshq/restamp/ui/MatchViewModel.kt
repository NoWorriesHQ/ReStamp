package com.noworrieshq.restamp.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noworrieshq.restamp.core.match.FileMatcher
import com.noworrieshq.restamp.core.match.ManualChoices
import com.noworrieshq.restamp.core.match.MatchResult
import com.noworrieshq.restamp.core.match.MediaFile
import com.noworrieshq.restamp.media.FolderScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Holds the two picked folders, their files, and how the files are paired. */
class MatchViewModel(app: Application) : AndroidViewModel(app) {
    private val scanner = FolderScanner(app)
    private val prefs = app.getSharedPreferences("folders", Context.MODE_PRIVATE)

    var sourceFolder by mutableStateOf<Uri?>(null); private set
    var targetFolder by mutableStateOf<Uri?>(null); private set
    var includeSubfolders by mutableStateOf(prefs.getBoolean(KEY_SUBFOLDERS, false)); private set
    var sources by mutableStateOf(emptyList<MediaFile>()); private set
    var targets by mutableStateOf(emptyList<MediaFile>()); private set
    var skipped by mutableStateOf(0); private set
    var manual by mutableStateOf(ManualChoices()); private set
    var result by mutableStateOf<MatchResult?>(null); private set
    var scanning by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    init {
        // Reopen the folders picked last time, if the app still has access.
        sourceFolder = prefs.getString(KEY_SOURCE, null)?.let(Uri::parse)?.takeIf(scanner::hasAccess)
        targetFolder = prefs.getString(KEY_TARGET, null)?.let(Uri::parse)?.takeIf(scanner::hasAccess)
        rescan()
    }

    fun pickSource(tree: Uri) {
        scanner.keepAccess(tree)
        sourceFolder = tree
        prefs.edit().putString(KEY_SOURCE, tree.toString()).apply()
        rescan()
    }

    fun pickTarget(tree: Uri) {
        scanner.keepAccess(tree)
        targetFolder = tree
        prefs.edit().putString(KEY_TARGET, tree.toString()).apply()
        rescan()
    }

    fun changeIncludeSubfolders(on: Boolean) {
        includeSubfolders = on
        prefs.edit().putBoolean(KEY_SUBFOLDERS, on).apply()
        rescan()
    }

    fun pair(target: MediaFile, source: MediaFile) = updateManual(manual.pair(target.id, source.id))
    fun unpair(target: MediaFile) = updateManual(manual.unpair(target.id))
    fun resetToAutomatic(target: MediaFile) = updateManual(manual.reset(target.id))
    fun resetAll() = updateManual(ManualChoices())

    fun suggestionsFor(target: MediaFile): List<MediaFile> = FileMatcher.suggestions(target, sources, limit = 5)

    fun rescan() {
        val source = sourceFolder ?: return
        val target = targetFolder ?: return
        scanning = true
        error = null
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val s = scanner.scan(source, includeSubfolders)
                    val t = if (target == source) s else scanner.scan(target, includeSubfolders)
                    Triple(s, t, FileMatcher.match(s.files, t.files, manual))
                }
            }.onSuccess { (s, t, r) ->
                sources = s.files
                targets = t.files
                skipped = if (target == source) s.skipped else s.skipped + t.skipped
                result = r
            }.onFailure {
                error = "Couldn't read the folders: ${it.message}"
            }
            scanning = false
        }
    }

    private fun updateManual(choices: ManualChoices) {
        manual = choices
        // Matching is quick (thousands of names in well under a second), so redo it in place.
        result = FileMatcher.match(sources, targets, choices)
    }

    private companion object {
        const val KEY_SOURCE = "source"
        const val KEY_TARGET = "target"
        const val KEY_SUBFOLDERS = "subfolders"
    }
}
