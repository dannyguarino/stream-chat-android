package io.getstream.chat.android.offline

import android.content.Context
import android.os.Handler
import androidx.annotation.CheckResult
import androidx.annotation.VisibleForTesting
import io.getstream.chat.android.client.BuildConfig.STREAM_CHAT_VERSION
import io.getstream.chat.android.client.ChatClient
import io.getstream.chat.android.client.api.models.FilterObject
import io.getstream.chat.android.client.api.models.QueryChannelRequest
import io.getstream.chat.android.client.api.models.QueryChannelsRequest
import io.getstream.chat.android.client.api.models.QuerySort
import io.getstream.chat.android.client.call.Call
import io.getstream.chat.android.client.call.CoroutineCall
import io.getstream.chat.android.client.call.await
import io.getstream.chat.android.client.call.toUnitCall
import io.getstream.chat.android.client.errors.ChatError
import io.getstream.chat.android.client.events.ChatEvent
import io.getstream.chat.android.client.events.MarkAllReadEvent
import io.getstream.chat.android.client.extensions.cidToTypeAndId
import io.getstream.chat.android.client.extensions.enrichWithCid
import io.getstream.chat.android.client.logger.ChatLogger
import io.getstream.chat.android.client.models.Attachment
import io.getstream.chat.android.client.models.Channel
import io.getstream.chat.android.client.models.ChannelMute
import io.getstream.chat.android.client.models.Config
import io.getstream.chat.android.client.models.Filters.`in`
import io.getstream.chat.android.client.models.Member
import io.getstream.chat.android.client.models.Message
import io.getstream.chat.android.client.models.Mute
import io.getstream.chat.android.client.models.Reaction
import io.getstream.chat.android.client.models.TypingEvent
import io.getstream.chat.android.client.models.User
import io.getstream.chat.android.client.models.UserEntity
import io.getstream.chat.android.client.utils.Result
import io.getstream.chat.android.client.utils.SyncStatus
import io.getstream.chat.android.client.utils.map
import io.getstream.chat.android.client.utils.observable.Disposable
import io.getstream.chat.android.core.ExperimentalStreamChatApi
import io.getstream.chat.android.core.internal.coroutines.DispatcherProvider
import io.getstream.chat.android.livedata.BuildConfig
import io.getstream.chat.android.offline.channel.ChannelController
import io.getstream.chat.android.offline.event.EventHandlerImpl
import io.getstream.chat.android.offline.experimental.channel.state.toMutableState
import io.getstream.chat.android.offline.experimental.channel.thread.state.toMutableState
import io.getstream.chat.android.offline.experimental.global.GlobalMutableState
import io.getstream.chat.android.offline.experimental.global.toMutableState
import io.getstream.chat.android.offline.experimental.plugin.OfflinePlugin
import io.getstream.chat.android.offline.experimental.querychannels.state.toMutableState
import io.getstream.chat.android.offline.extensions.applyPagination
import io.getstream.chat.android.offline.extensions.cancelMessage
import io.getstream.chat.android.offline.extensions.createChannel
import io.getstream.chat.android.offline.extensions.isPermanent
import io.getstream.chat.android.offline.extensions.loadMessageById
import io.getstream.chat.android.offline.extensions.loadOlderMessages
import io.getstream.chat.android.offline.extensions.sendGiphy
import io.getstream.chat.android.offline.extensions.shuffleGiphy
import io.getstream.chat.android.offline.extensions.users
import io.getstream.chat.android.offline.message.attachment.UploadAttachmentsNetworkType
import io.getstream.chat.android.offline.message.users
import io.getstream.chat.android.offline.model.ChannelConfig
import io.getstream.chat.android.offline.model.ConnectionState
import io.getstream.chat.android.offline.model.SyncState
import io.getstream.chat.android.offline.querychannels.QueryChannelsController
import io.getstream.chat.android.offline.repository.RepositoryFacade
import io.getstream.chat.android.offline.repository.builder.RepositoryFacadeBuilder
import io.getstream.chat.android.offline.repository.database.ChatDatabase
import io.getstream.chat.android.offline.repository.domain.user.UserRepository
import io.getstream.chat.android.offline.request.AnyChannelPaginationRequest
import io.getstream.chat.android.offline.request.QueryChannelsPaginationRequest
import io.getstream.chat.android.offline.request.toAnyChannelPaginationRequest
import io.getstream.chat.android.offline.service.sync.OfflineSyncFirebaseMessagingHandler
import io.getstream.chat.android.offline.thread.ThreadController
import io.getstream.chat.android.offline.usecase.DeleteMessage
import io.getstream.chat.android.offline.usecase.DeleteReaction
import io.getstream.chat.android.offline.usecase.EditMessage
import io.getstream.chat.android.offline.usecase.GetChannelController
import io.getstream.chat.android.offline.usecase.HideChannel
import io.getstream.chat.android.offline.usecase.LeaveChannel
import io.getstream.chat.android.offline.usecase.LoadNewerMessages
import io.getstream.chat.android.offline.usecase.MarkAllRead
import io.getstream.chat.android.offline.usecase.MarkRead
import io.getstream.chat.android.offline.usecase.QueryChannels
import io.getstream.chat.android.offline.usecase.QueryMembers
import io.getstream.chat.android.offline.usecase.SearchUsersByName
import io.getstream.chat.android.offline.usecase.SendMessage
import io.getstream.chat.android.offline.usecase.SendReaction
import io.getstream.chat.android.offline.usecase.ShowChannel
import io.getstream.chat.android.offline.usecase.WatchChannel
import io.getstream.chat.android.offline.utils.CallRetryService
import io.getstream.chat.android.offline.utils.DefaultRetryPolicy
import io.getstream.chat.android.offline.utils.Event
import io.getstream.chat.android.offline.utils.RetryPolicy
import io.getstream.chat.android.offline.utils.validateCid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Date
import java.util.InputMismatchException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val MESSAGE_LIMIT = 30
private const val MEMBER_LIMIT = 30
private const val INITIAL_CHANNEL_OFFSET = 0
private const val CHANNEL_LIMIT = 30

/**
 * The Chat Domain exposes StateFlow objects to make it easier to build your chat UI.
 * It intercepts the various low level events to ensure data stays in sync.
 * Offline storage is handled using Room
 *
 * A different Room database is used for different users. That's why it's mandatory to specify the user id when
 * initializing the ChatRepository
 *
 * chatDomain.channel(type, id) returns a controller object with channel specific state flow objects
 * chatDomain.queryChannels(query) returns a state flow object for the specific queryChannels query
 *
 * chatDomain.online state flow object indicates if you're online or not
 * chatDomain.totalUnreadCount state flow object returns the current unread count for this user
 * chatDomain.muted the list of muted users
 * chatDomain.banned if the current user is banned or not
 * chatDomain.channelUnreadCount state flow object returns the number of unread channels for this user
 * chatDomain.errorEvents events for errors that happen while interacting with the chat
 *
 */
@OptIn(ExperimentalStreamChatApi::class)
internal class ChatDomainImpl internal constructor(
    internal var client: ChatClient,
    @VisibleForTesting
    internal var db: ChatDatabase? = null,
    private val mainHandler: Handler,
    override var offlineEnabled: Boolean = true,
    internal var recoveryEnabled: Boolean = true,
    override var userPresence: Boolean = false,
    internal var backgroundSyncEnabled: Boolean = false,
    internal var appContext: Context,
    private val offlinePlugin: OfflinePlugin,
    internal val uploadAttachmentsNetworkType: UploadAttachmentsNetworkType = UploadAttachmentsNetworkType.NOT_ROAMING,
    override val retryPolicy: RetryPolicy = DefaultRetryPolicy(),
) : ChatDomain {
    internal constructor(
        client: ChatClient,
        handler: Handler,
        offlineEnabled: Boolean,
        recoveryEnabled: Boolean,
        userPresence: Boolean,
        backgroundSyncEnabled: Boolean,
        appContext: Context,
        offlinePlugin: OfflinePlugin,
    ) : this(
        client = client,
        db = null,
        mainHandler = handler,
        offlineEnabled = offlineEnabled,
        recoveryEnabled = recoveryEnabled,
        userPresence = userPresence,
        backgroundSyncEnabled = backgroundSyncEnabled,
        appContext = appContext,
        offlinePlugin = offlinePlugin,
    )

    // Synchronizing ::retryFailedEntities execution since it is called from multiple places. The shared resource is DB.stream_chat_message table.
    private val entitiesRetryMutex = Mutex()

    internal val job = SupervisorJob()
    internal var scope = CoroutineScope(job + DispatcherProvider.IO)

    @VisibleForTesting
    val defaultConfig: Config = Config(connectEventsEnabled = true, muteEnabled = true)

    /*
     * It is necessary to initialize `RepositoryFacade` lazily to give time to the real RepositoryFacade to be set
     * instead of using `createNoOpRepos()`, otherwise the SDK will create a in memory Room's database which will be later
     * replaced with the real database. This creates a resource leak because, when the second database is created, the first one is
     * not closed by room.
     */
    internal var repos: RepositoryFacade
        get() = _repos ?: createNoOpRepos().also { repositoryFacade ->
            _repos = repositoryFacade
        }
        set(value) {
            _repos = value
        }

    private var _repos: RepositoryFacade? = null

    private val globalState: GlobalMutableState = offlinePlugin.globalState.toMutableState()

    override val user: StateFlow<User?> by globalState::user

    /** if the client connection has been initialized */
    override val initialized: StateFlow<Boolean> = globalState.initialized

    /**
     * StateFlow<Boolean> that indicates if we are currently online
     */
    override val connectionState: StateFlow<ConnectionState> = globalState.connectionState

    /**
     * The total unread message count for the current user.
     * Depending on your app you'll want to show this or the channelUnreadCount
     */
    override val totalUnreadCount: StateFlow<Int> = globalState.totalUnreadCount

    /**
     * the number of unread channels for the current user
     */
    override val channelUnreadCount: StateFlow<Int> = globalState.channelUnreadCount

    /**
     * list of users that you've muted
     */
    override val muted: StateFlow<List<Mute>> = globalState.muted

    /**
     * List of channels you've muted
     */
    override val channelMutes: StateFlow<List<ChannelMute>> = globalState.channelMutes

    /**
     * if the current user is banned or not
     */
    override val banned: StateFlow<Boolean> = globalState.banned

    /**
     * The error event state flow object is triggered when errors in the underlying components occur.
     */
    override val errorEvents: StateFlow<Event<ChatError>> = globalState.errorEvents

    /** the event subscription */
    private var eventSubscription: Disposable = EMPTY_DISPOSABLE

    /** stores the mapping from cid to ChannelController */
    private val activeChannelMapImpl: ConcurrentHashMap<String, ChannelController> = ConcurrentHashMap()

    override val typingUpdates: StateFlow<TypingEvent> = globalState.typingUpdates

    private val activeQueryMapImpl: ConcurrentHashMap<String, QueryChannelsController> = ConcurrentHashMap()

    @VisibleForTesting
    internal var eventHandler: EventHandlerImpl = EventHandlerImpl(this, client)
    private var logger = ChatLogger.get("Domain")

    private val cleanTask = object : Runnable {
        override fun run() {
            clean()
            mainHandler.postDelayed(this, 1000)
        }
    }
    private val syncStateFlow: MutableStateFlow<SyncState?> = MutableStateFlow(null)
    internal var initJob: Deferred<*>? = null

    private val offlineSyncFirebaseMessagingHandler = OfflineSyncFirebaseMessagingHandler()

    /** State flow of latest cached users. Usually it has size of 100 as max size of LRU cache in [UserRepository].*/
    internal var latestUsers: StateFlow<Map<String, User>> = MutableStateFlow(emptyMap())
        private set

    private fun clearUnreadCountState() {
        globalState._totalUnreadCount.value = 0
        globalState._channelUnreadCount.value = 0
    }

    private fun clearConnectionState() {
        globalState._initialized.value = false
        globalState._connectionState.value = ConnectionState.OFFLINE
        globalState._banned.value = false
        globalState._mutedUsers.value = emptyList()
        activeChannelMapImpl.clear()
        activeQueryMapImpl.clear()
        latestUsers = MutableStateFlow(emptyMap())
    }

    internal fun setUser(user: User) {
        clearConnectionState()
        clearUnreadCountState()

        globalState._user.value = user

        repos = RepositoryFacadeBuilder {
            context(appContext)
            database(db)
            currentUser(user)
            scope(scope)
            defaultConfig(defaultConfig)
            setOfflineEnabled(offlineEnabled)
        }.build()

        latestUsers = repos.observeLatestUsers()
        // load channel configs from Room into memory
        initJob = scope.async {
            // fetch the configs for channels
            repos.cacheChannelConfigs()

            // load the current user from the db
            val syncState = repos.selectSyncState(user.id) ?: SyncState(user.id)

            // retrieve the last time the user marked all as read and handle it as an event
            syncState.markedAllReadAt
                ?.let { MarkAllReadEvent(user = user, createdAt = it) }
                ?.let { eventHandler.handleEvent(it) }

            syncState.also { syncStateFlow.value = it }

            // Sync cached channels
            val cachedChannelsCids = repos.selectAllCids()
            replayEventsForChannels(cachedChannelsCids)
        }

        if (client.isSocketConnected()) {
            setOnline()
        }
        startListening()
        initClean()
    }

    init {
        logger.logI("Initializing ChatDomain with version " + getVersion())

        // if the user is already defined, just call setUser ourselves
        val current = client.getCurrentUser()
        if (current != null) {
            setUser(current)
        }
        // past behaviour was to set the user on the chat domain
        // the new syntax is to automatically pick up changes from the client
        // listen to future user changes
        client.preSetUserListeners.add {
            setUser(it)
        }
        // disconnect if the low level client disconnects
        client.disconnectListeners.add {
            scope.launch {
                disconnect()
            }
        }

        if (backgroundSyncEnabled) {
            client.setPushNotificationReceivedListener { channelType, channelId ->
                offlineSyncFirebaseMessagingHandler.syncMessages(appContext, "$channelType:$channelId")
            }
        }
    }

    internal suspend fun updateCurrentUser(me: User) {
        if (me.id != user.value?.id) {
            throw InputMismatchException("received connect event for user with id ${me.id} while chat domain is configured for user with id ${user.value?.id}. create a new ChatDomain when connecting a different user.")
        }
        globalState._user.value = me
        repos.insertCurrentUser(me)
        globalState._mutedUsers.value = me.mutes
        globalState._channelMutes.value = me.channelMutes
        setTotalUnreadCount(me.totalUnreadCount)
        setChannelUnreadCount(me.unreadChannels)
        setBanned(me.banned)
    }

    internal suspend fun storeSyncState(): SyncState? {
        syncStateFlow.value?.let { _syncState ->
            val newSyncState = _syncState.copy(
                activeChannelIds = getActiveChannelCids(),
            )
            repos.insertSyncState(newSyncState)
            syncStateFlow.value = newSyncState
        }

        return syncStateFlow.value
    }

    override suspend fun disconnect() {
        storeSyncState()
        job.cancelChildren()
        stopListening()
        stopClean()
        clearConnectionState()
        offlineSyncFirebaseMessagingHandler.cancel(appContext)
        activeChannelMapImpl.values.forEach(ChannelController::cancelJobs)
        eventHandler.clear()
        activeChannelMapImpl.clear()
        activeQueryMapImpl.clear()
        offlinePlugin.clear()
    }

    override fun getVersion(): String {
        return STREAM_CHAT_VERSION + "-" + BuildConfig.BUILD_TYPE
    }

    private fun stopClean() {
        mainHandler.removeCallbacks(cleanTask)
    }

    private fun initClean() {
        mainHandler.postDelayed(cleanTask, 5000)
    }

    internal fun callRetryService() = CallRetryService(retryPolicy, client)

    @Deprecated(
        message = "This utility method is extracted to CallRetryService",
        replaceWith = ReplaceWith("ChatDomainImpl::callRetryService::runAndRetry")
    )
    suspend fun <T : Any> runAndRetry(runnable: () -> Call<T>): Result<T> = callRetryService().runAndRetry(runnable)

    fun addError(error: ChatError) {
        globalState._errorEvent.value = Event(error)
    }

    fun isActiveChannel(cid: String): Boolean {
        return activeChannelMapImpl.containsKey(cid)
    }

    fun getActiveChannelCids(): List<String> {
        return activeChannelMapImpl.keys().toList()
    }

    @VisibleForTesting
    fun addActiveChannel(cid: String, channelController: ChannelController) {
        activeChannelMapImpl[cid] = channelController
    }

    fun setChannelUnreadCount(newCount: Int) {
        globalState._channelUnreadCount.value = newCount
    }

    fun setBanned(newBanned: Boolean) {
        globalState._banned.value = newBanned
    }

    fun setTotalUnreadCount(newCount: Int) {
        globalState._totalUnreadCount.value = newCount
    }

    /**
     * Start listening to chat events and keep the room database in sync
     */
    private fun startListening() {
        if (eventSubscription.isDisposed) {
            eventSubscription = client.subscribe {
                eventHandler.handleEvents(listOf(it))
            }
        }
    }

    /**
     * Stop listening to chat events
     */
    private fun stopListening() {
        eventSubscription.dispose()
    }

    internal fun channel(c: Channel): ChannelController {
        return channel(c.type, c.id)
    }

    internal fun channel(cid: String): ChannelController {
        val (channelType, channelId) = cid.cidToTypeAndId()
        return channel(channelType, channelId)
    }

    /**
     * @return [Channel] object from repository if exists, null otherwise.
     */
    internal suspend fun getCachedChannel(cid: String): Channel? {
        return repos.selectChannelWithoutMessages(cid)
    }

    internal fun channel(
        channelType: String,
        channelId: String,
    ): ChannelController {
        val cid = "%s:%s".format(channelType, channelId)
        if (!activeChannelMapImpl.containsKey(cid)) {
            val channelController = ChannelController(
                mutableState = offlinePlugin.state.channel(channelType, channelId).toMutableState(),
                channelLogic = offlinePlugin.logic.channel(channelType, channelId),
                client = client,
                domainImpl = this,
            )
            activeChannelMapImpl[cid] = channelController
            addTypingChannel(channelController)
        }
        return activeChannelMapImpl.getValue(cid)
    }

    internal fun allActiveChannels(): List<ChannelController> =
        activeChannelMapImpl.values.toList()

    fun generateMessageId(): String {
        return user.value!!.id + "-" + UUID.randomUUID().toString()
    }

    private fun addTypingChannel(channelController: ChannelController) {
        scope.launch { globalState._typingChannels.emitAll(channelController.typing) }
    }

    internal fun setOffline() {
        globalState._connectionState.value = ConnectionState.OFFLINE
    }

    internal fun setOnline() {
        globalState._connectionState.value = ConnectionState.CONNECTED
    }

    internal fun setConnecting() {
        globalState._connectionState.value = ConnectionState.CONNECTING
    }

    internal fun setInitialized() {
        globalState._initialized.value = true
    }

    override fun isOnline(): Boolean = globalState.isOnline()

    override fun isOffline(): Boolean = globalState.isOffline()

    override fun isConnecting(): Boolean = globalState.isConnecting()

    override fun isInitialized(): Boolean = globalState.isInitialized()

    override fun getActiveQueries(): List<QueryChannelsController> {
        return activeQueryMapImpl.values.toList()
    }

    /**
     * queryChannels
     * - first read the current results from Room
     * - if we are online make the API call to update results
     */
    fun queryChannels(
        filter: FilterObject,
        sort: QuerySort<Channel>,
    ): QueryChannelsController =
        activeQueryMapImpl.getOrPut("${filter.hashCode()}-${sort.hashCode()}") {
            val mutableState = offlinePlugin.state.queryChannels(filter, sort).toMutableState()
            val logic = offlinePlugin.logic.queryChannels(filter, sort)
            QueryChannelsController(domainImpl = this, mutableState = mutableState, queryChannelsLogic = logic)
        }

    private suspend fun queryEvents(cids: List<String>): Result<List<ChatEvent>> =
        client.getSyncHistory(cids, syncStateFlow.value?.lastSyncedAt ?: Date()).await()

    /**
     * replay events for all active channels
     * ensures that the cid you provide is active
     *
     * @param cid ensures that the channel with this id is active
     */
    internal suspend fun replayEvents(cid: String? = null): Result<List<ChatEvent>> {
        // wait for the active channel info to load
        initJob?.join()
        // make a list of all channel ids
        val cids = activeChannelMapImpl.keys().toList().toMutableList()
        cid?.let {
            channel(it)
            cids.add(it)
        }

        return replayEventsForChannels(cids)
    }

    private suspend fun replayEventsForChannels(cids: List<String>): Result<List<ChatEvent>> {
        val now = Date()

        return if (cids.isNotEmpty()) {
            queryEvents(cids).also { resultChatEvent ->
                if (resultChatEvent.isSuccess) {
                    eventHandler.handleEventsInternal(resultChatEvent.data(), isFromSync = true)
                    updateLastSyncDate(now)
                }
            }
        } else {
            Result(emptyList())
        }
    }

    /**
     * Updates [SyncState.lastSyncedAt] exposed via [syncStateFlow] with a given date.
     *
     * @param date The new last sync date.
     */
    private fun updateLastSyncDate(date: Date) {
        syncStateFlow.value?.let { syncStateFlow.value = it.copy(lastSyncedAt = date) }
    }

    /**
     * There are several scenarios in which we need to recover events
     * - Connection is lost and comes back (everything should be considered stale, so use recover all)
     * - App goes to the background and comes back (everything should be considered stale, so use recover all)
     * - We run a queryChannels or channel.watch call and encounter an offline state/or API error (should recover just that query or channel)
     * - A reaction, message or channel fails to be created. We should retry this every health check (30 seconds or so)
     *
     * Calling connectionRecovered triggers:
     * - queryChannels for the active query (at most 3) that need recovery
     * - queryChannels for any channels that need recovery
     * - channel.watch for channels that are not returned by the server
     * - event recovery for those channels
     * - API calls to create local channels, messages and reactions
     */
    suspend fun connectionRecovered(recoverAll: Boolean = false) {
        // 0. ensure load is complete
        initJob?.join()

        val online = isOnline()

        // 1. Retry any failed requests first (synchronous)
        if (online) {
            retryFailedEntities()
        }

        // 2. update the results for queries that are actively being shown right now (synchronous)
        val updatedChannelIds = mutableSetOf<String>()
        val queriesToRetry = activeQueryMapImpl.values
            .toList()
            .filter { it.recoveryNeeded.value || recoverAll }
            .take(3)
        for (queryChannelController in queriesToRetry) {
            val pagination = QueryChannelsPaginationRequest(
                queryChannelController.sort,
                INITIAL_CHANNEL_OFFSET,
                CHANNEL_LIMIT,
                MESSAGE_LIMIT,
                MEMBER_LIMIT
            )
            val response = queryChannelController.runQueryOnline(pagination)
            if (response.isSuccess) {
                queryChannelController.updateOnlineChannels(response.data(), true)
                updatedChannelIds.addAll(response.data().map { it.cid })
            }
        }
        // 3. update the data for all channels that are being show right now...
        // exclude ones we just updated
        // (synchronous)
        val cids: List<String> = activeChannelMapImpl
            .entries
            .asSequence()
            .filter { it.value.recoveryNeeded || recoverAll }
            .filterNot { updatedChannelIds.contains(it.key) }
            .take(30)
            .map { it.key }
            .toList()

        logger.logI("recovery called: recoverAll: $recoverAll, online: $online retrying ${queriesToRetry.size} queries and ${cids.size} channels")

        var missingChannelIds = listOf<String>()
        if (cids.isNotEmpty() && online) {
            val filter = `in`("cid", cids)
            val request = QueryChannelsRequest(filter, 0, 30)
            val result = client.queryChannelsInternal(request).await()
            if (result.isSuccess) {
                val channels = result.data()
                val foundChannelIds = channels.map { it.id }
                for (c in channels) {
                    val channelController = this.channel(c)
                    channelController.updateDataFromChannel(c)
                }
                missingChannelIds = cids.filterNot { foundChannelIds.contains(it) }
                storeStateForChannels(channels)
            }
            // create channels that are not present on the API
            for (c in missingChannelIds) {
                val channelController = this.channel(c)
                channelController.watch()
            }
        }
        // 4. recover missing events
        val activeChannelCids = getActiveChannelCids()
        if (activeChannelCids.isNotEmpty()) {
            replayEventsForChannels(activeChannelCids)
        } else {
            // Last sync date is being updated after replaying events for active channels.
            // We should also update it if we don't have active channels but sync was completed.
            updateLastSyncDate(Date())
        }
    }

    internal suspend fun retryFailedEntities() {
        entitiesRetryMutex.withLock {
            // retry channels, messages and reactions in that order..
            val channels = retryChannels()
            val messages = retryMessages()
            val reactions = retryReactions()
            logger.logI("Retried ${channels.size} channel entities, ${messages.size} messages and ${reactions.size} reaction entities")
        }
    }

    @VisibleForTesting
    internal suspend fun retryChannels(): List<Channel> {
        return repos.selectChannelsSyncNeeded().onEach { channel ->
            val result = client.createChannel(
                channel.type,
                channel.id,
                channel.members.map(UserEntity::getUserId),
                channel.extraData
            ).await()

            when {
                result.isSuccess -> {
                    channel.syncStatus = SyncStatus.COMPLETED
                    repos.insertChannel(channel)
                }
                result.isError && result.error().isPermanent() -> {
                    channel.syncStatus = SyncStatus.FAILED_PERMANENTLY
                    repos.insertChannel(channel)
                }
            }
        }
    }

    @VisibleForTesting
    internal suspend fun retryMessages(): List<Message> {
        return retryMessagesWithSyncedAttachments() + retryMessagesWithPendingAttachments()
    }

    /**
     * Retries messages with [SyncStatus.AWAITING_ATTACHMENTS] status.
     */
    private suspend fun retryMessagesWithPendingAttachments(): List<Message> {
        val retriedMessages = repos.selectMessagesWaitForAttachments()

        val (failedMessages, needToBeSync) = retriedMessages.partition { message ->
            message.attachments.any { it.uploadState is Attachment.UploadState.Failed }
        }

        failedMessages.forEach { markMessageAsFailed(it) }

        needToBeSync.forEach { message -> channel(message.cid).retrySendMessage(message) }

        return retriedMessages
    }

    /**
     * Retries messages with [SyncStatus.SYNC_NEEDED] status.
     * Messages to retry should have all attachments synchronized or don't have them at all.
     *
     * @throws IllegalArgumentException when message contains non-synchronized attachments
     */
    private suspend fun retryMessagesWithSyncedAttachments(): List<Message> {
        val (messages, nonCorrectStateMessages) = repos.selectMessagesSyncNeeded().partition {
            it.attachments.all { attachment -> attachment.uploadState === Attachment.UploadState.Success }
        }
        if (nonCorrectStateMessages.isNotEmpty()) {
            val message = nonCorrectStateMessages.first()
            val attachmentUploadState =
                message.attachments.firstOrNull { it.uploadState != Attachment.UploadState.Success }
                    ?: Attachment.UploadState.Success
            logger.logE(
                "Logical error. Messages with non-synchronized attachments should have another sync status!" +
                    "\nMessage has ${message.syncStatus} syncStatus, while attachment has $attachmentUploadState upload state"
            )
        }

        messages.forEach { message ->
            val channelClient = client.channel(message.cid)

            val result = when {
                message.deletedAt != null -> {
                    logger.logD("Deleting message: ${message.id}")
                    channelClient.deleteMessage(message.id).await()
                }
                message.updatedLocallyAt != null -> {
                    logger.logD("Updating message: ${message.id}")
                    client.updateMessageInternal(message).await()
                }
                else -> {
                    logger.logD("Sending message: ${message.id}")
                    channelClient.sendMessage(message).await()
                }
            }

            if (result.isSuccess) {
                repos.insertMessage(message.copy(syncStatus = SyncStatus.COMPLETED))
            } else if (result.isError && result.error().isPermanent()) {
                markMessageAsFailed(message)
            }
        }

        return messages
    }

    private suspend fun markMessageAsFailed(message: Message) =
        repos.insertMessage(message.copy(syncStatus = SyncStatus.FAILED_PERMANENTLY, updatedLocallyAt = Date()))

    @VisibleForTesting
    internal suspend fun retryReactions(): List<Reaction> {
        return repos.selectReactionsSyncNeeded().onEach { reaction ->
            val result = if (reaction.deletedAt != null) {
                client.deleteReaction(reaction.messageId, reaction.type).await()
            } else {
                client.sendReaction(reaction, reaction.enforceUnique).await()
            }

            if (result.isSuccess) {
                reaction.syncStatus = SyncStatus.COMPLETED
                repos.insertReaction(reaction)
            } else if (result.error().isPermanent()) {
                reaction.syncStatus = SyncStatus.FAILED_PERMANENTLY
                repos.insertReaction(reaction)
            }
        }
    }

    suspend fun storeStateForChannel(channel: Channel) {
        return storeStateForChannels(listOf(channel))
    }

    suspend fun storeStateForChannels(channelsResponse: Collection<Channel>) {
        val users = mutableMapOf<String, User>()
        val configs: MutableCollection<ChannelConfig> = mutableSetOf()
        // start by gathering all the users
        val messages = mutableListOf<Message>()
        for (channel in channelsResponse) {

            users.putAll(channel.users().associateBy { it.id })
            configs += ChannelConfig(channel.type, channel.config)

            channel.messages.forEach { message ->
                message.enrichWithCid(channel.cid)
                users.putAll(message.users().associateBy { it.id })
            }

            messages.addAll(channel.messages)
        }

        repos.storeStateForChannels(
            configs = configs,
            users = users.values.toList(),
            channels = channelsResponse,
            messages = messages
        )

        logger.logI("storeStateForChannels stored ${channelsResponse.size} channels, ${configs.size} configs, ${users.size} users and ${messages.size} messages")
    }

    suspend fun selectAndEnrichChannel(
        channelId: String,
        pagination: QueryChannelRequest,
    ): Channel? = selectAndEnrichChannels(listOf(channelId), pagination.toAnyChannelPaginationRequest()).getOrNull(0)

    suspend fun selectAndEnrichChannels(
        channelIds: List<String>,
        pagination: QueryChannelsPaginationRequest,
    ): List<Channel> = selectAndEnrichChannels(channelIds, pagination.toAnyChannelPaginationRequest())

    private suspend fun selectAndEnrichChannels(
        channelIds: List<String>,
        pagination: AnyChannelPaginationRequest,
    ): List<Channel> = repos.selectChannels(channelIds, pagination).applyPagination(pagination)

    override fun clean() {
        for (channelController in activeChannelMapImpl.values.toList()) {
            channelController.clean()
        }
    }

    override fun getChannelConfig(channelType: String): Config =
        repos.selectChannelConfig(channelType)?.config ?: defaultConfig

    // region use-case functions

    override fun getChannelController(cid: String): Call<ChannelController> = GetChannelController(this).invoke(cid)

    override fun watchChannel(cid: String, messageLimit: Int): Call<ChannelController> =
        WatchChannel(this).invoke(cid, messageLimit)

    override fun queryChannels(
        filter: FilterObject,
        sort: QuerySort<Channel>,
        limit: Int,
        messageLimit: Int,
        memberLimit: Int,
    ): Call<QueryChannelsController> = QueryChannels(this).invoke(filter, sort, limit, messageLimit, memberLimit)

    /**
     * Returns a thread controller for the given channel and message id.
     *
     * @param cid The full channel id. ie messaging:123.
     * @param parentId The message id for the parent of this thread.
     *
     * @see io.getstream.chat.android.offline.thread.ThreadController
     */
    @CheckResult
    override fun getThread(cid: String, parentId: String): Call<ThreadController> {
        validateCid(cid)
        return CoroutineCall(scope) {
            Result.success(
                channel(cid).getThread(
                    offlinePlugin.state.thread(parentId).toMutableState(),
                    offlinePlugin.logic.thread(parentId)
                )
            )
        }
    }

    override fun loadOlderMessages(cid: String, messageLimit: Int): Call<Channel> =
        client.loadOlderMessages(cid, messageLimit)

    override fun loadNewerMessages(cid: String, messageLimit: Int): Call<Channel> =
        LoadNewerMessages(this).invoke(cid, messageLimit)

    override fun loadMessageById(
        cid: String,
        messageId: String,
        olderMessagesOffset: Int,
        newerMessagesOffset: Int,
    ): Call<Message> {
        return CoroutineCall(scope) {
            try {
                validateCid(cid)
                val channelController = channel(cid)
                channelController.loadMessageById(messageId, newerMessagesOffset, olderMessagesOffset)
            } catch (e: IllegalArgumentException) {
                Result(ChatError(e.message))
            }
        }
    }

    override fun queryChannelsLoadMore(
        filter: FilterObject,
        sort: QuerySort<Channel>,
        limit: Int,
        messageLimit: Int,
        memberLimit: Int,
    ): Call<List<Channel>> {
        return CoroutineCall(scope) {
            val queryChannelsController = queryChannels(filter, sort)
            val oldChannels = queryChannelsController.channels.value
            val pagination = queryChannelsController.loadMoreRequest(
                channelLimit = limit,
                messageLimit = messageLimit,
                memberLimit = memberLimit,
            )
            queryChannelsController.runQuery(pagination).map { it - oldChannels.toSet() }
        }
    }

    override fun queryChannelsLoadMore(
        filter: FilterObject,
        sort: QuerySort<Channel>,
        messageLimit: Int,
    ): Call<List<Channel>> = queryChannelsLoadMore(
        filter = filter,
        sort = sort,
        limit = CHANNEL_LIMIT,
        messageLimit = messageLimit,
        memberLimit = MEMBER_LIMIT,
    )

    override fun queryChannelsLoadMore(
        filter: FilterObject,
        sort: QuerySort<Channel>,
    ): Call<List<Channel>> = queryChannelsLoadMore(
        filter = filter,
        sort = sort,
        limit = CHANNEL_LIMIT,
        messageLimit = MESSAGE_LIMIT,
        memberLimit = MEMBER_LIMIT,
    )

    /**
     * Loads more messages for the specified thread.
     *
     * @param cid The full channel id i. e. messaging:123.
     * @param parentId The parentId of the thread.
     * @param messageLimit How many new messages to load.
     */
    override fun threadLoadMore(cid: String, parentId: String, messageLimit: Int): Call<List<Message>> {
        validateCid(cid)
        require(parentId.isNotEmpty()) { "parentId can't be empty" }

        return CoroutineCall(scope) {
            val threadController = getThread(cid, parentId).execute().data()
            threadController.loadOlderMessages(messageLimit)
        }
    }

    override fun createChannel(channel: Channel): Call<Channel> = client.createChannel(channel)

    override fun sendMessage(message: Message): Call<Message> = SendMessage(this).invoke(message)

    override fun cancelMessage(message: Message): Call<Boolean> = client.cancelMessage(message)

    /**
     * Performs giphy shuffle operation. Removes the original "ephemeral" message from local storage.
     * Returns new "ephemeral" message with new giphy url.
     * API call to remove the message is retried according to the retry policy specified on the chatDomain
     *
     * @param message The message to send.
     * @see io.getstream.chat.android.offline.utils.RetryPolicy
     */
    override fun shuffleGiphy(message: Message): Call<Message> = client.shuffleGiphy(message)

    /**
     * Sends selected giphy message to the channel. Removes the original "ephemeral" message from local storage.
     * Returns new "ephemeral" message with new giphy url.
     * API call to remove the message is retried according to the retry policy specified on the chatDomain.
     *
     * @param message The message to send.
     * @see io.getstream.chat.android.offline.utils.RetryPolicy
     */
    override fun sendGiphy(message: Message): Call<Message> = client.sendGiphy(message)

    @Deprecated(
        message = "ChatDomain.editMessage is deprecated. Use function ChatClient::updateMessage instead",
        replaceWith = ReplaceWith(
            expression = "ChatClient.instance().updateMessage(message)",
            imports = arrayOf("io.getstream.chat.android.client.ChatClient")
        ),
        level = DeprecationLevel.WARNING
    )
    override fun editMessage(message: Message): Call<Message> = EditMessage(this).invoke(message)

    override fun deleteMessage(message: Message, hard: Boolean): Call<Message> =
        DeleteMessage(this).invoke(message, hard)

    override fun deleteMessage(message: Message): Call<Message> = deleteMessage(message, false)

    override fun sendReaction(cid: String, reaction: Reaction, enforceUnique: Boolean): Call<Reaction> =
        SendReaction(this).invoke(cid, reaction, enforceUnique)

    override fun deleteReaction(cid: String, reaction: Reaction): Call<Message> =
        DeleteReaction(this).invoke(cid, reaction)

    override fun markRead(cid: String): Call<Boolean> = MarkRead(this).invoke(cid)

    override fun markAllRead(): Call<Boolean> = MarkAllRead(this).invoke()

    override fun hideChannel(cid: String, keepHistory: Boolean): Call<Unit> =
        HideChannel(this).invoke(cid, keepHistory)

    override fun showChannel(cid: String): Call<Unit> = ShowChannel(this).invoke(cid)

    override fun leaveChannel(cid: String): Call<Unit> = LeaveChannel(this).invoke(cid)

    override fun deleteChannel(cid: String): Call<Unit> = client.channel(cid).delete().toUnitCall()

    override fun searchUsersByName(
        querySearch: String,
        offset: Int,
        userLimit: Int,
        userPresence: Boolean,
    ): Call<List<User>> = SearchUsersByName(this).invoke(querySearch, offset, userLimit, userPresence)

    override fun queryMembers(
        cid: String,
        offset: Int,
        limit: Int,
        filter: FilterObject,
        sort: QuerySort<Member>,
        members: List<Member>,
    ): Call<List<Member>> = QueryMembers(this).invoke(cid, offset, limit, filter, sort, members)
// end region

    private fun createNoOpRepos(): RepositoryFacade = RepositoryFacadeBuilder {
        context(appContext)
        scope(scope)
        database(db)
        defaultConfig(defaultConfig)
        setOfflineEnabled(false)
    }.build()

    companion object {
        val EMPTY_DISPOSABLE = object : Disposable {
            override val isDisposed: Boolean = true
            override fun dispose() {}
        }
    }
}
