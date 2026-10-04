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
import com.qtekfun.ultimatemail.domain.conversation.Neighbours
import com.qtekfun.ultimatemail.domain.conversation.ReaderList
import com.qtekfun.ultimatemail.domain.conversation.ReaderNeighbours
import com.qtekfun.ultimatemail.sync.engine.BodyResult
import com.qtekfun.ultimatemail.sync.engine.DownloadAttachment
import com.qtekfun.ultimatemail.sync.engine.DownloadResult
import com.qtekfun.ultimatemail.sync.engine.LoadMessageBody
import com.qtekfun.ultimatemail.sync.engine.SyncProblem
import com.qtekfun.ultimatemail.ui.inbox.MovePickerLauncher
import com.qtekfun.ultimatemail.ui.inbox.MovePickerRequest
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
    val view: ConversationView? = null,
    /** The conversations just before and after this one in the list it was opened from. */
    val neighbours: Neighbours = Neighbours()
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
@Suppress("TooManyFunctions", "LongParameterList") // One collaborator per thing the reader does.
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val reader: ConversationReader,
    private val actions: ConversationActions,
    private val loadMessageBody: LoadMessageBody,
    private val downloadAttachment: DownloadAttachment,
    private val composeLauncher: ComposeLauncher,
    private val movePicker: MovePickerLauncher,
    private val readerNeighbours: ReaderNeighbours,
    settings: SettingsRepository,
    private val notices: NoticeCenter,
    @IoDispatcher private val io: CoroutineDispatcher
) : ViewModel() {
    private val ref = MutableStateFlow<ConversationRef?>(null)
    private val list = MutableStateFlow<ReaderList?>(null)
    private val local = MutableStateFlow(ConversationLocal())
    private val presenter = ConversationPresenter()
    private var starting: Job? = null
    private val loadingBodies = mutableSetOf<Long>()

    private val channel = Channel<ConversationEvent>(Channel.BUFFERED)

    /** One-off events for the screen; each is delivered once. */
    val events: Flow<ConversationEvent> = channel.receiveAsFlow()

    /** The message to show in the snackbar now, if any. */
    val notice: StateFlow<ConversationNotice?> = notices.notice

    val state: StateFlow<ConversationState> = combine(
        ref,
        list,
        ::Pair
    ).flatMapLatest { (target, from) ->
        if (target == null) {
            flowOf(ConversationState())
        } else {
            combine(
                reader.observe(target),
                local,
                settings.settings.map { it.remoteContent }.distinctUntilChanged(),
                readerNeighbours.observe(target, from)
            ) { data, screen, remotePolicy, around ->
                ConversationState(
                    ref = target,
                    loaded = true,
                    view = data.takeIf { it.messages.isNotEmpty() }
                        ?.let { presenter.present(it, screen, target.folderPath, remotePolicy) },
                    neighbours = around
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
     * [from] is the list it was opened from, which the previous and next arrows walk.
     */
    fun open(target: ConversationRef, from: ReaderList? = null) {
        list.value = from
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

    /** Opens the folder/label picker for every message of the conversation. */
    fun moveTo() {
        val view = state.value.view ?: return
        val accountId = ref.value?.accountId ?: return
        viewModelScope.launch {
            val handles = reader.handlesOf(view.messages.map { it.id })
            if (handles.isNotEmpty()) movePicker.open(MovePickerRequest(accountId, handles))
        }
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
            notices.post(impossible)
            return
        }
        viewModelScope.launch {
            val undo = actions.move(view.messages.map { it.id }, target)
            if (undo != null) {
                notices.post(done, undo = PendingUndo(setOf(accountId)) { actions.undo(undo) })
            }
            channel.send(ConversationEvent.Close)
        }
    }

    /** The reader tapped "Undo" on the notice [noticeId]: the move is taken back. */
    fun undo(noticeId: Long) {
        val pending = notices.takeUndo(noticeId) ?: return
        viewModelScope.launch { pending.revert() }
    }

    /** The undo window of the notice [noticeId] is over: the move is sent to the server. */
    fun commit(noticeId: Long) = notices.commit(noticeId)

    /** Shows a message that does not come from here (the screen did something that failed). */
    fun report(kind: NoticeKind) {
        notices.post(kind)
    }

    /** A message without undo was shown. */
    fun noticeShown(noticeId: Long) = notices.shown(noticeId)

    /** Starts writing a message from the newest one. */
    fun compose(mode: ComposeMode) {
        val newest = state.value.view?.newest ?: return
        val target = ref.value ?: return
        val request = ComposeRequest(target.accountId, target.folderPath, newest.id, mode)
        if (!composeLauncher.start(request)) notices.post(NoticeKind.COMPOSE_FAILED)
    }

    /** Starts a new, empty message from the account of the conversation. */
    fun composeNew() {
        val accountId = ref.value?.accountId ?: return
        if (!composeLauncher.startNew(accountId)) notices.post(NoticeKind.COMPOSE_FAILED)
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

                DownloadResult.Gone -> notices.post(NoticeKind.ATTACHMENT_GONE)

                DownloadResult.AuthenticationRequired, is DownloadResult.Failed ->
                    notices.post(NoticeKind.ATTACHMENT_FAILED)
            }
        }
    }

    private fun Set<Long>.toggled(id: Long) = if (id in this) this - id else this + id

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
