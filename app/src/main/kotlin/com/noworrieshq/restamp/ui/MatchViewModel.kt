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
import com.noworrieshq.restamp.media.MediaFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data object Bulk : Screen
    data class BulkFile(val targetId: String) : Screen
    data object View : Screen
    data object Single : Screen
}

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

    /** Tags of the listed files, and writing into the edited copies. */
    val tags = TagStore(MediaFiles(app), viewModelScope)

    /** Which screen is showing. Kept here so it survives rotation. */
    var screen by mutableStateOf<Screen>(Screen.Home)

    /** The file picked for "view or edit", or the two files picked for a single copy. */
    var viewing by mutableStateOf<MediaFile?>(null); private set
    var singleSource by mutableStateOf<MediaFile?>(null); private set
    var singleTarget by mutableStateOf<MediaFile?>(null); private set

    fun view(uri: Uri) { viewing = picked(uri); screen = Screen.View }
    fun pickSingleSource(uri: Uri) { singleSource = picked(uri) }
    fun pickSingleTarget(uri: Uri) { singleTarget = picked(uri) }
    fun clearSinglePair() { singleTarget = null }

    /** Goes back one level. Returns false at the home screen, so the app can close. */
    fun back(): Boolean {
        screen = when (screen) {
            Screen.Home -> return false
            is Screen.BulkFile -> Screen.Bulk
            Screen.Single -> if (singleTarget != null) { singleTarget = null; Screen.Single } else Screen.Home
            else -> Screen.Home
        }
        return true
    }

    private fun picked(uri: Uri): MediaFile {
        scanner.keepFileAccess(uri)
        return MediaFile(uri.toString(), scanner.fileName(uri))
    }

    /** Matched edited copies the person left out of the next bulk copy. */
    var excluded by mutableStateOf(emptySet<String>()); private set

    fun setIncluded(target: MediaFile, included: Boolean) {
        excluded = if (included) excluded - target.id else excluded + target.id
    }

    fun includeAll(included: Boolean) {
        excluded = if (included) emptySet() else result?.pairs?.mapTo(HashSet()) { it.target.id }.orEmpty()
    }

    /** Pairs ticked for the next bulk copy. */
    val selectedPairs get() = result?.pairs?.filter { it.target.id !in excluded }.orEmpty()

    /** Looks up an edited copy by id, with its original when it has one. */
    fun findTarget(id: String): Pair<MediaFile, MediaFile?>? {
        val r = result ?: return null
        r.pairFor(id)?.let { return it.target to it.source }
        val t = r.ambiguous.firstOrNull { it.target.id == id }?.target ?: r.unmatchedTargets.firstOrNull { it.id == id }
        return t?.let { it to null }
    }

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
                tags.clear()
                loadTags(r)
            }.onFailure {
                error = "Couldn't read the folders: ${it.message}"
            }
            scanning = false
        }
    }

    private fun updateManual(choices: ManualChoices) {
        manual = choices
        // Matching is quick (thousands of names in well under a second), so redo it in place.
        result = FileMatcher.match(sources, targets, choices).also(::loadTags)
    }

    private fun loadTags(r: MatchResult) {
        // Edited copies first, since those are what the list shows statuses for.
        tags.ensureLoaded(r.pairs.map { it.target } + r.unmatchedTargets + r.ambiguous.map { it.target })
        tags.ensureLoaded(r.pairs.map { it.source }.distinctBy { it.id })
    }

    private companion object {
        const val KEY_SOURCE = "source"
        const val KEY_TARGET = "target"
        const val KEY_SUBFOLDERS = "subfolders"
    }
}
