package com.dinoe.clockwise.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dinoe.clockwise.ClockwiseApp
import com.dinoe.clockwise.data.Tag
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TagsViewModel(application: Application) : AndroidViewModel(application) {
    private val tagDao = (application as ClockwiseApp).database.tagDao()

    val tags: StateFlow<List<Tag>> = tagDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addTag(name: String, color: Int, onResult: (Boolean) -> Unit) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            val success = tagDao.getByName(trimmed) == null
            if (success) {
                tagDao.insert(Tag(name = trimmed, color = color))
            }
            onResult(success)
        }
    }

    fun renameTag(tag: Tag, name: String, color: Int, onResult: (Boolean) -> Unit) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            val existing = tagDao.getByName(trimmed)
            val success = existing == null || existing.id == tag.id
            if (success) {
                tagDao.update(tag.copy(name = trimmed, color = color))
            }
            onResult(success)
        }
    }

    fun deleteTag(tag: Tag) {
        viewModelScope.launch {
            tagDao.deleteEventTagRefs(tag.id)
            tagDao.deleteAlarmTagRefs(tag.id)
            tagDao.delete(tag)
        }
    }
}
