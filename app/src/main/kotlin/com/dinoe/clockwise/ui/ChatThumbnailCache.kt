package com.dinoe.clockwise.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Asynchronous outcome of one chat attachment thumbnail. */
sealed interface ChatThumbnailState {
    /** The message has no attachment. */
    data object None : ChatThumbnailState

    data object Loading : ChatThumbnailState

    data class Ready(val bitmap: ImageBitmap) : ChatThumbnailState

    /** The payload could not be decoded; the message renders without an image. */
    data object Failed : ChatThumbnailState
}

val ChatThumbnailState.bitmapOrNull: ImageBitmap?
    get() = (this as? ChatThumbnailState.Ready)?.bitmap

val ChatThumbnailState.isLoading: Boolean
    get() = this is ChatThumbnailState.Loading

/**
 * Bounded cache of decoded chat thumbnails, confined to the Main thread. Entries are keyed by the
 * attachment's base64 payload, an immutable value shared with the transcript item that owns it, so
 * scrolling the LazyColumn or recreating the AI tab reuses the bitmap instead of decoding it again.
 * Decoding itself runs on [Dispatchers.Default].
 */
class ChatThumbnailCache {
    private val ready = BoundedLruCache<String, ImageBitmap>(MAX_THUMBNAILS)
    private val failed = BoundedLruCache<String, Unit>(MAX_THUMBNAILS)

    /** Cached outcome without decoding; [ChatThumbnailState.Loading] means a decode is required. */
    fun stateOf(base64: String?): ChatThumbnailState {
        if (base64 == null) return ChatThumbnailState.None
        ready.get(base64)?.let { return ChatThumbnailState.Ready(it) }
        return if (failed.get(base64) != null) {
            ChatThumbnailState.Failed
        } else {
            ChatThumbnailState.Loading
        }
    }

    suspend fun load(base64: String): ChatThumbnailState {
        val cached = stateOf(base64)
        if (cached != ChatThumbnailState.Loading) return cached
        val bitmap = withContext(Dispatchers.Default) { decode(base64) }
        return if (bitmap != null) {
            ready.put(base64, bitmap)
            ChatThumbnailState.Ready(bitmap)
        } else {
            failed.put(base64, Unit)
            ChatThumbnailState.Failed
        }
    }

    fun evict(base64: String?) {
        if (base64 == null) return
        ready.remove(base64)
        failed.remove(base64)
    }

    /** Releases every entry that no longer backs a message of the open session. */
    fun retainOnly(base64s: Collection<String>) {
        ready.retainOnly(base64s)
        failed.retainOnly(base64s)
    }

    fun clear() {
        ready.clear()
        failed.clear()
    }

    private fun decode(base64: String): ImageBitmap? = runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()

    private companion object {
        // Previews are bounded to 240 px on their longest side, so a full cache stays small.
        const val MAX_THUMBNAILS = 48
    }
}

/** Current thumbnail state of [base64], decoding it once off the Main thread when needed. */
@Composable
fun rememberChatThumbnail(cache: ChatThumbnailCache, base64: String?): ChatThumbnailState {
    var state by remember(cache, base64) { mutableStateOf(cache.stateOf(base64)) }
    LaunchedEffect(cache, base64) {
        if (base64 != null) state = cache.load(base64)
    }
    return state
}
