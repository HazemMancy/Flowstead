/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.ui.help

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.thermodock.core.help.Help
import app.thermodock.data.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface HelpEffect {
    data class OpenProject(val id: String) : HelpEffect
    data class Message(val text: String) : HelpEffect
}

/** Opens an example as a new project; the help pages themselves need no state. */
@HiltViewModel
class HelpViewModel @Inject constructor(private val repository: ProjectRepository) : ViewModel() {
    private val _effects = Channel<HelpEffect>(Channel.BUFFERED)
    val effects: Flow<HelpEffect> = _effects.receiveAsFlow()

    fun openExample(id: String) = viewModelScope.launch {
        val example = Help.examples.firstOrNull { it.id == id }
        val fs = Help.example(id)
        if (example == null || fs == null) {
            _effects.send(HelpEffect.Message("That example is not in this version"))
            return@launch
        }
        _effects.send(HelpEffect.OpenProject(repository.create("Example: ${example.name}", fs)))
    }
}
