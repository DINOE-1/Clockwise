package com.dinoe.clockwise.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

const val MAX_TAGS_PER_ITEM = 5

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class Tag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    // ARGB color chosen from the tag color palette; 0 means no color.
    @ColumnInfo(defaultValue = "0") val color: Int = 0,
)

@Entity(tableName = "event_tags", primaryKeys = ["eventId", "tagId"], indices = [Index("tagId")])
data class EventTagCrossRef(
    val eventId: Long,
    val tagId: Long,
)

@Entity(tableName = "alarm_tags", primaryKeys = ["alarmId", "tagId"], indices = [Index("tagId")])
data class AlarmTagCrossRef(
    val alarmId: Long,
    val tagId: Long,
)
