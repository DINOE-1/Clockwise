package com.dinoe.clockwise.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.dinoe.clockwise.ClockwiseApp
import com.dinoe.clockwise.data.Tag
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TagsViewModel(application: Application) : AndroidViewModel(application) {
    private val database = (application as ClockwiseApp).database
    private val tagDao = database.tagDao()

    val tags: StateFlow<List<Tag>> = tagDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addTag(name: String, color: Int, onResult: (Boolean) -> Unit) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            val success = runCatching {
                database.withTransaction {
                    if (tagDao.getByName(trimmed) == null) {
                        tagDao.insert(Tag(name = trimmed, color = color))
                        true
                    } else {
                        false
                    }
                }
            }.getOrDefault(false)
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
            val success = runCatching {
                database.withTransaction {
                    val existing = tagDao.getByName(trimmed)
                    if (existing == null || existing.id == tag.id) {
                        tagDao.update(tag.copy(name = trimmed, color = color))
                        true
                    } else {
                        false
                    }
                }
            }.getOrDefault(false)
            onResult(success)
        }
    }

    fun deleteTag(tag: Tag) {
        viewModelScope.launch {
            runCatching {
                database.withTransaction {
                    tagDao.deleteEventTagRefs(tag.id)
                    tagDao.deleteAlarmTagRefs(tag.id)
                    tagDao.delete(tag)
                }
            }
        }
    }
}
