package com.beeregg2001.komorebi.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.repository.ReserveProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class AutoReserveCancelUi {
    object Closed : AutoReserveCancelUi()
    object Loading : AutoReserveCancelUi()
    data class Candidates(val items: List<AutoReserveCancelCandidate>) : AutoReserveCancelUi()
    data class Review(val preview: AutoReserveCancelPreview) : AutoReserveCancelUi()
    object Running : AutoReserveCancelUi()
    data class Done(val message: String) : AutoReserveCancelUi()
    data class Error(val message: String) : AutoReserveCancelUi()
}

@HiltViewModel
class AutoReserveCancellationViewModel @Inject constructor(private val provider: ReserveProvider) : ViewModel() {
    private val _state = MutableStateFlow<AutoReserveCancelUi>(AutoReserveCancelUi.Closed)
    val state = _state.asStateFlow()
    private var job: Job? = null

    fun search(keyword: String) {
        if (_state.value is AutoReserveCancelUi.Running) return
        job?.cancel()
        _state.value = AutoReserveCancelUi.Loading
        job = viewModelScope.launch {
            provider.findAutoReserveCancellationCandidates(keyword).fold(
                onSuccess = { candidates ->
                    if (candidates.size == 1) prepare(candidates.single().id)
                    else _state.value = AutoReserveCancelUi.Candidates(candidates)
                },
                onFailure = { _state.value = AutoReserveCancelUi.Error(it.message ?: "候補を取得できませんでした。") }
            )
        }
    }

    fun select(id: Int) {
        val candidates = _state.value as? AutoReserveCancelUi.Candidates ?: return
        if (candidates.items.none { it.id == id }) return
        _state.value = AutoReserveCancelUi.Loading
        job = viewModelScope.launch { prepare(id) }
    }

    private suspend fun prepare(id: Int) {
        provider.previewAutoReserveCancellation(id).fold(
            onSuccess = { _state.value = AutoReserveCancelUi.Review(it) },
            onFailure = { _state.value = AutoReserveCancelUi.Error(it.message ?: "対象を確認できませんでした。") }
        )
    }

    fun confirm() {
        val review = _state.value as? AutoReserveCancelUi.Review ?: return
        // 連打を、API呼び出し前に状態遷移で抑止する。
        _state.value = AutoReserveCancelUi.Running
        job = viewModelScope.launch {
            provider.confirmAutoReserveCancellation(review.preview.token).fold(
                onSuccess = { _state.value = AutoReserveCancelUi.Done(it.message) },
                onFailure = { _state.value = AutoReserveCancelUi.Error(it.message ?: "取り消しを確認できませんでした。") }
            )
        }
    }

    fun dismiss() {
        if (_state.value is AutoReserveCancelUi.Running) return
        job?.cancel()
        _state.value = AutoReserveCancelUi.Closed
    }
}
