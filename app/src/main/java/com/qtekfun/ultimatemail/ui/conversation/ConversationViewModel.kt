// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatemail.data.settings.SettingsRepository
import com.qtekfun.ultimatemail.di.IoDispatcher
import com.qtekfun.ultimatemail.domain.conversation.BodyFailure
import com.qtekfun.ultimatemail.domain.conversation.BodyLoad
import com.qtekfun.ultimatemail.domain.conversation.ComposeLauncher
import com.qtekfun.ultimatemail.domain.conversation.ComposeMode
import com.qtekfun.ultimatemail.domain.conversation.ComposeRequest
import com.qtekfun.ultimatemail.domain.conversation.ConversationActions
import com.qtekfun.ultimatemail.domain.conversation.ConversationLocal
import com.qtekfun.ultimatemail.domain.conversation.ConversationPresenter
import com.qtekfun.ultimatemail.domain.conversation.ConversationReader
import com.qtekfun.ultimatemail.domain.conversation.ConversationRef
import com.qtekfun.ultimatemail.domain.conversation.ConversationView
import com.qtekfun.ultimatemail.domain.conversation.MoveUndo
import com.qtekfun.ultimatemail.sync.engine.BodyResult
import com.qtekfun.ultimatemail.sync.engine.DownloadAttachment
import com.qtekfun.ultimatemail.sync.engine.DownloadResult
import com.qtekfun.ultimatemail.sync.engine.LoadMessageBody
import com.qtekfun.ultimatemail.sync.engine.SyncProblem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the conversation screen shows. [loaded] is false until Room answered for [ref]; once it
 * did, a null [view] means the conversation is not there (any more).
 */
data class ConversationState(
    val ref: ConversationRef? = null,
    val loaded: Boolean = false,
    val view: ConversationView? = null
) {
    val missing: Boolean get() = loaded && view == null
}

/** What to do with an attachment once it is on the device. */
enum class AttachmentAction { OPEN, SHARE, SAVE }

/** One-off things the screen has to do, which are not state. */
sealed interface ConversationEvent {
    /** Leave the conversation: it was archived, deleted or marked unread. */
    data object Close : ConversationEvent

    /** An attachment is ready at [path]; do [action] with it. */
    data class AttachmentReady(
        val action: AttachmentAction,
        val path: String,
        val mimeType: String,
        val name: String
    ) : ConversationEvent
}

/** The short messages shown in the snackbar. */
enum class NoticeKind {
    ARCHIVED,
    DELETED,
    NO_ARCHIVE_FOLDER,
    NO_TRASH_FOLDER,
    COMPOSE_SOON,
    ATTACHMENT_FAILED,
    ATTACHMENT_GONE,
    ATTACHMENT_SAVED,
    ATTACHMENT_NOT_SAVED,
    NO_APP_FOR_ATTACHMENT
}

/** A snackbar message; [undoable] ones offer "Undo" and are answered with undo or commit. */
data class ConversationNotice(val id: Long, val kind: NoticeKind, val undoable: Boolean)

/**
 * The open conversation (RF-03, RF-04, RF-05): which messages are expanded, their bodies and
 * attachments, and the actions on them. Room is the source of truth; what is only about the
 * screen (expanded, details shown, remote content allowed) is kept in [ConversationLocal]. The
 * business rules live in [ConversationPresenter], [ConversationActions] and the sync use cases.
 *
 * It is scoped to the activity, like the other screens' view models, so an archive or delete
 * still has its undo notice after the screen is gone.
 */
// One function per thing the reader can do on the screen; splitting the class would only scatter
// the state they share.
@Suppress("TooManyFunctions", "LongParameterList")
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val reader: ConversationReader,
    private val actions: ConversationActions,
    private val loadMessageBody: LoadMessageBody,
    private val downloadAttachment: DownloadAttachment,
    private val composeLauncher: ComposeLauncher,
    settings: SettingsRepository,
    @IoDispatcher private val io: CoroutineDispatcher
) : ViewModel() {
    private val ref = MutableStateFlow<ConversationRef?>(null)
    private val local = MutableStateFlow(ConversationLocal())
    private val presenter = ConversationPresenter()
    private var starting: Job? = null
    private val loadingBodies = mutableSetOf<Long>()
    private var nextNoticeId = 0L
    private val pendingMoves = mutableMapOf<Long, Pair<Long, MoveUndo>>()

    private val channel = Channel<ConversationEvent>(Channel.BUFFERED)

    /** One-off events for the screen; each is delivered once. */
    val events: Flow<ConversationEvent> = channel.receiveAsFlow()

    private val currentNotice = MutableStateFlow<ConversationNotice?>(null)

    /** The message to show in the snackbar now, if any. */
    val notice: StateFlow<ConversationNotice?> = currentNotice

    val state: StateFlow<ConversationState> = ref.flatMapLatest { target ->
        if (target == null) {
            flowOf(ConversationState())
        } else {
            combine(
                reader.observe(target),
                local,
                settings.settings.map { it.remoteContent }.distinctUntilChanged()
            ) { data, screen, remotePolicy ->
                ConversationState(
                    ref = target,
                    loaded = true,
                    view = data.takeIf { it.messages.isNotEmpty() }
                        ?.let { presenter.present(it, screen, target.folderPath, remotePolicy) }
                )
            }.flowOn(io)
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        ConversationState()
    )

    /**
     * Opens [target]. Asking for the one already open keeps its state (rotation, coming back);
     * another starts clean, with the first unread message open, or the newest when all are read.
     */
    fun open(target: ConversationRef) {
        if (ref.value == target) return
        starting?.cancel()
        // Whatever the screen of an earlier conversation never got to do is not for this one.
        generateSequence { channel.tryReceive().getOrNull() }.toList()
        local.value = ConversationLocal()
        loadingBodies.clear()
        ref.value = target
        starting = viewModelScope.launch {
            val data = reader.observe(target).first { it.messages.isNotEmpty() }
            val first = data.messages.firstOrNull { !it.seen } ?: data.messages.last()
            local.update { it.copy(expanded = setOf(first.id)) }
            onExpanded(first.id)
        }
    }

    /** Opens or closes a message. Opening one marks it read and loads its body. */
    fun toggle(messageId: Long) {
        val expanded = messageId in local.value.expanded
        local.update { it.copy(expanded = it.expanded.toggled(messageId)) }
        if (!expanded) onExpanded(messageId)
    }

    private fun onExpanded(messageId: Long) {
        viewModelScope.launch { actions.markRead(messageId) }
        ensureBody(messageId)
    }

    /** Fetches the body of a message unless it is already here, and the images it shows. */
    fun ensureBody(messageId: Long) {
        if (!loadingBodies.add(messageId)) return
        local.update { it.copy(bodyLoads = it.bodyLoads + (messageId to BodyLoad.Loading)) }
        viewModelScope.launch {
            val result = loadMessageBody(messageId)
            local.update { screen ->
                val loads = when (val failure = result.failure()) {
                    null -> screen.bodyLoads - messageId
                    else -> screen.bodyLoads + (messageId to BodyLoad.Failed(failure))
                }
                screen.copy(bodyLoads = loads)
            }
            loadingBodies.remove(messageId)
            if (result is BodyResult.Loaded) downloadAttachment.fetchInline(messageId)
        }
    }

    private fun BodyResult.failure(): BodyFailure? = when (this) {
        is BodyResult.Loaded -> null

        BodyResult.NotFound -> BodyFailure.GONE

        BodyResult.AuthenticationRequired -> BodyFailure.SIGN_IN

        is BodyResult.Failed ->
            if (problem == SyncProblem.NETWORK || problem == SyncProblem.TIMEOUT) {
                BodyFailure.OFFLINE
            } else {
                BodyFailure.OTHER
            }
    }

    fun toggleDetails(messageId: Long) =
        local.update { it.copy(detailsShown = it.detailsShown.toggled(messageId)) }

    fun toggleQuoted(messageId: Long) =
        local.update { it.copy(quotedShown = it.quotedShown.toggled(messageId)) }

    /** Loads the remote images of one message; the choice is not remembered for others. */
    fun allowRemoteContent(messageId: Long) =
        local.update { it.copy(remoteAllowed = it.remoteAllowed + messageId) }

    /** Switches one message between the dark theme's colours and the sender's own. */
    fun toggleOriginalColors(messageId: Long) =
        local.update { it.copy(originalColors = it.originalColors.toggled(messageId)) }

    /** Stars or unstars the conversation, which is its newest message. */
    fun toggleStar() {
        val newest = state.value.view?.newest ?: return
        viewModelScope.launch { actions.setStarred(newest.id, !newest.flagged) }
    }

    /** Marks the conversation (its newest message) unread and goes back to the list. */
    fun markUnread() {
        val newest = state.value.view?.newest ?: return
        viewModelScope.launch {
            actions.markUnread(newest.id)
            channel.send(ConversationEvent.Close)
        }
    }

    fun archive() {
        val view = state.value.view ?: return
        val target = view.targets.archivePath.takeIf { view.targets.canArchive }
        moveAway(view, target, NoticeKind.ARCHIVED, NoticeKind.NO_ARCHIVE_FOLDER)
    }

    fun delete() {
        val view = state.value.view ?: return
        val target = view.targets.trashPath.takeIf { view.targets.canDelete }
        moveAway(view, target, NoticeKind.DELETED, NoticeKind.NO_TRASH_FOLDER)
    }

    private fun moveAway(
        view: ConversationView,
        target: String?,
        done: NoticeKind,
        impossible: NoticeKind
    ) {
        val accountId = ref.value?.accountId
        if (target == null || accountId == null) {
            post(impossible, undoable = false)
            return
        }
        viewModelScope.launch {
            val undo = actions.move(view.messages.map { it.id }, target)
            if (undo != null) {
                val id = post(done, undoable = true)
                pendingMoves[id] = accountId to undo
            }
            channel.send(ConversationEvent.Close)
        }
    }

    /** The reader tapped "Undo" on the notice [noticeId]: the move is taken back. */
    fun undo(noticeId: Long) {
        val pending = pendingMoves.remove(noticeId) ?: return
        clearNotice(noticeId)
        viewModelScope.launch { actions.undo(pending.second) }
    }

    /** The undo window of the notice [noticeId] is over: the move is sent to the server. */
    fun commit(noticeId: Long) {
        pendingMoves.remove(noticeId)?.let { actions.sync(it.first) }
        clearNotice(noticeId)
    }

    /** Shows a message that does not come from here (the screen did something that failed). */
    fun report(kind: NoticeKind) {
        post(kind, undoable = false)
    }

    /** A message without undo was shown. */
    fun noticeShown(noticeId: Long) = clearNotice(noticeId)

    /** A new notice replaces the one shown; a move waiting on that one's undo is sent now. */
    private fun post(kind: NoticeKind, undoable: Boolean): Long {
        currentNotice.value?.takeIf { it.undoable }?.let { commit(it.id) }
        val id = ++nextNoticeId
        currentNotice.value = ConversationNotice(id, kind, undoable)
        return id
    }

    private fun clearNotice(noticeId: Long) {
        currentNotice.update { current -> current?.takeIf { it.id != noticeId } }
    }

    /** Starts writing a message from the newest one (placeholder until T18). */
    fun compose(mode: ComposeMode) {
        val newest = state.value.view?.newest ?: return
        val target = ref.value ?: return
        val request = ComposeRequest(target.accountId, target.folderPath, newest.id, mode)
        if (!composeLauncher.start(request)) post(NoticeKind.COMPOSE_SOON, undoable = false)
    }

    /**
     * Gets an attachment onto the device (only now, never ahead) and then asks the screen to
     * [action] it. Does nothing while the same attachment is already downloading.
     */
    fun attachment(attachmentId: Long, action: AttachmentAction) {
        if (attachmentId in local.value.downloading) return
        val file = state.value.view?.messages?.flatMap { it.attachments }
            ?.firstOrNull { it.id == attachmentId } ?: return
        local.update { it.copy(downloading = it.downloading + attachmentId) }
        viewModelScope.launch {
            val result = try {
                downloadAttachment(attachmentId)
            } finally {
                local.update { it.copy(downloading = it.downloading - attachmentId) }
            }
            when (result) {
                is DownloadResult.Ready -> channel.send(
                    ConversationEvent.AttachmentReady(action, result.path, file.mimeType, file.name)
                )

                DownloadResult.Gone -> post(NoticeKind.ATTACHMENT_GONE, undoable = false)

                DownloadResult.AuthenticationRequired, is DownloadResult.Failed ->
                    post(NoticeKind.ATTACHMENT_FAILED, undoable = false)
            }
        }
    }

    private fun Set<Long>.toggled(id: Long) = if (id in this) this - id else this + id

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
