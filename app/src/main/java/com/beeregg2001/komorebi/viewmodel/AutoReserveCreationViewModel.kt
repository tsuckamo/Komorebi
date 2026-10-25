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

sealed class AutoReserveCreateUi {
    object Loading : AutoReserveCreateUi()
    data class Candidates(val items: List<AutoReserveCreateCandidate>) : AutoReserveCreateUi()
    data class Options(val candidate: AutoReserveCreateCandidate, val options: AutoReserveCreateOptions, val error: String? = null) : AutoReserveCreateUi()
    data class Review(val preview: AutoReserveCreatePreview) : AutoReserveCreateUi()
    object Running : AutoReserveCreateUi()
    data class Done(val result: AutoReserveCreateResult) : AutoReserveCreateUi()
    data class Error(val message: String) : AutoReserveCreateUi()
}

@HiltViewModel
class AutoReserveCreationViewModel @Inject constructor(private val provider: ReserveProvider) : ViewModel() {
    private val _state = MutableStateFlow<AutoReserveCreateUi>(AutoReserveCreateUi.Loading)
    val state = _state.asStateFlow()
    private var job: Job? = null
    fun search(keyword: String) {
        if (_state.value is AutoReserveCreateUi.Running) return
        job?.cancel()
        _state.value = AutoReserveCreateUi.Loading
        job = viewModelScope.launch {
            provider.findAutoReserveCreationCandidates(keyword).fold(
                { _state.value = AutoReserveCreateUi.Candidates(it) },
                { _state.value = AutoReserveCreateUi.Error(it.message ?: "番組を取得できませんでした。") }
            )
        }
    }
    fun select(candidate: AutoReserveCreateCandidate) {
        val current = _state.value as? AutoReserveCreateUi.Candidates ?: return
        if (candidate !in current.items) return
        _state.value = AutoReserveCreateUi.Options(candidate, AutoReserveCreateOptions(candidate.keyword, candidate.week))
    }
    fun edit(options: AutoReserveCreateOptions) {
        val current = _state.value as? AutoReserveCreateUi.Options ?: return
        _state.value = current.copy(options = options, error = null)
    }
    fun preview() {
        val current = _state.value as? AutoReserveCreateUi.Options ?: return
        _state.value = AutoReserveCreateUi.Loading
        job = viewModelScope.launch {
            provider.previewAutoReserveCreation(current.candidate.programId, current.options).fold(
                { _state.value = AutoReserveCreateUi.Review(it) },
                { _state.value = current.copy(error = it.message ?: "対象を確認できませんでした。") }
            )
        }
    }
    fun revise() {
        val current = _state.value as? AutoReserveCreateUi.Review ?: return
        _state.value = AutoReserveCreateUi.Options(current.preview.candidate, current.preview.options)
    }
    fun confirm() {
        val current = _state.value as? AutoReserveCreateUi.Review ?: return
        _state.value = AutoReserveCreateUi.Running
        job = viewModelScope.launch {
            provider.confirmAutoReserveCreation(current.preview.token).fold(
                { _state.value = AutoReserveCreateUi.Done(it) },
                { _state.value = AutoReserveCreateUi.Error(it.message ?: "登録結果を確認できませんでした。") }
            )
        }
    }
    fun dismiss() { if (_state.value !is AutoReserveCreateUi.Running) job?.cancel() }
}
