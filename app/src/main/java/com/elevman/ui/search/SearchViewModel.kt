package com.elevman.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.elevman.data.CodeHit
import com.elevman.data.CodeSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the search screen shows: the text in the box, and the results for it grouped by elevator
 * model name. [isSearching] is true from a change of the query until its results arrive, so the
 * screen does not claim "no match" while it is still looking.
 */
data class SearchUiState(
    val query: String,
    val groups: List<Pair<String, List<CodeHit>>>,
    val isSearching: Boolean = false,
)

/**
 * Holds the [SearchUiState]. Every change of the query runs [CodeSearch.search] on
 * [Dispatchers.IO]; a newer query cancels the one still running, so the results shown always
 * belong to the last query typed.
 *
 * [scope] ends when the view model is cleared. It defaults to the main thread.
 */
class SearchViewModel(
    private val search: CodeSearch,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel(scope) {

    private val mutableState = MutableStateFlow(SearchUiState(query = "", groups = emptyList()))
    val state: StateFlow<SearchUiState> = mutableState.asStateFlow()

    private var searchJob: Job? = null

    /** Call on the main thread. The query is in [state] at once; the results follow. */
    fun onQueryChange(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            searchJob = null
            mutableState.value = SearchUiState(query = query, groups = emptyList())
            return
        }
        mutableState.update { it.copy(query = query, isSearching = true) }
        searchJob = scope.launch {
            val hits = withContext(Dispatchers.IO) { search.search(query) }
            // Only if this is still the query in the box: a result that lost the race to a newer
            // query must not overwrite it.
            mutableState.update {
                if (it.query == query) SearchUiState(query = query, groups = groupByModel(hits)) else it
            }
        }
    }

    companion object {
        fun factory(search: CodeSearch) = viewModelFactory {
            initializer { SearchViewModel(search) }
        }
    }
}

/** Groups [hits] by model name. The groups, and the hits in each, keep the order of [hits]. */
internal fun groupByModel(hits: List<CodeHit>): List<Pair<String, List<CodeHit>>> =
    hits.groupBy { it.modelName }.map { (name, group) -> name to group }
