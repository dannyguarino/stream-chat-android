package io.getstream.chat.ui.sample.feature.chat.info.group

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.getstream.chat.android.client.ChatClient
import io.getstream.chat.android.client.call.await
import io.getstream.chat.android.client.channel.ChannelClient
import io.getstream.chat.android.client.models.ChannelMute
import io.getstream.chat.android.client.models.Member
import io.getstream.chat.android.livedata.ChatDomain
import io.getstream.chat.android.livedata.utils.Event
import io.getstream.chat.android.ui.common.extensions.isOwnerOrAdmin
import io.getstream.chat.ui.sample.common.name
import kotlinx.coroutines.launch

class GroupChatInfoViewModel(
    private val cid: String,
    private val chatDomain: ChatDomain = ChatDomain.instance(),
    private val chatClient: ChatClient = ChatClient.instance(),
) : ViewModel() {

    private val channelClient: ChannelClient = chatClient.channel(cid)
    private val _state = MediatorLiveData<State>()
    private val _events = MutableLiveData<Event<UiEvent>>()
    private val _errorEvents: MutableLiveData<Event<ErrorEvent>> = MutableLiveData()
    val events: LiveData<Event<UiEvent>> = _events
    val state: LiveData<State> = _state
    val errorEvents: LiveData<Event<ErrorEvent>> = _errorEvents

    init {
        _state.value = INITIAL_STATE
        chatDomain.getChannelController(cid).enqueue { result ->
            if (result.isSuccess) {
                val controller = result.data()
                // Update channel mute status
                chatDomain.user.value?.channelMutes?.let(::updateChannelMuteStatus)

                // Update members
                _state.addSource(controller.members, this::updateMembers)

                getOwnerOrAdmin(controller.members.value)?.let { member ->
                    _state.value = _state.value?.copy(
                        isCurrentUserOwnerOrAdmin = chatDomain.user.value?.id == member.getUserId()
                    )
                }

                _state.addSource(controller.offlineChannelData) { channelData ->
                    _state.value = _state.value?.copy(channelName = channelData.name)
                }
            }
        }
    }

    private fun getOwnerOrAdmin(members: List<Member>?): Member? {
        return members?.firstOrNull { member ->
            member.isOwnerOrAdmin
        }
    }

    fun onAction(action: Action) {
        when (action) {
            is Action.NameChanged -> changeGroupName(action.name)
            is Action.MemberClicked -> handleMemberClick(action.member)
            Action.MembersSeparatorClicked -> _state.value = _state.value!!.copy(shouldExpandMembers = true)
            is Action.MuteChannelClicked -> switchGroupMute(action.isEnabled)
            is Action.ChannelMutesUpdated -> updateChannelMuteStatus(action.channelMutes)
            Action.LeaveChannelClicked -> leaveChannel()
        }
    }

    private fun handleMemberClick(member: Member) {
        if (member.getUserId() != chatDomain.user.value?.id) {
            val currentState = _state.value!!
            _events.value = Event(UiEvent.ShowMemberOptions(member, currentState.channelName))
        }
    }

    private fun changeGroupName(name: String) {
        viewModelScope.launch {
            val result = channelClient.update(message = null, mapOf("name" to name)).await()
            if (result.isError) {
                _errorEvents.postValue(Event(ErrorEvent.ChangeGroupNameError))
            }
        }
    }

    private fun leaveChannel() {
        viewModelScope.launch {
            val result = chatClient.getCurrentUser()?.let { user ->
                chatClient.removeMembers(channelClient.channelType, channelClient.channelId, listOf(user.id)).await()
            }
            if (result?.isSuccess == true) {
                _events.value = Event(UiEvent.RedirectToHome)
            } else {
                _errorEvents.postValue(Event(ErrorEvent.LeaveChannelError))
            }
        }
    }

    private fun updateMembers(members: List<Member>) {
        val currentState = _state.value!!
        _state.value =
            currentState.copy(
                members = members,
                shouldExpandMembers = currentState.shouldExpandMembers ?: false || members.size <= COLLAPSED_MEMBERS_COUNT,
                membersToShowCount = members.size - COLLAPSED_MEMBERS_COUNT
            )
    }

    private fun updateChannelMuteStatus(channelMutes: List<ChannelMute>) {
        _state.value = _state.value!!.copy(channelMuted = channelMutes.any { it.channel.cid == cid })
    }

    private fun switchGroupMute(isEnabled: Boolean) {
        viewModelScope.launch {
            val result = if (isEnabled) {
                channelClient.mute().await()
            } else {
                channelClient.unmute().await()
            }
            if (result.isError) {
                _errorEvents.postValue(Event(ErrorEvent.MuteChannelError))
            }
        }
    }

    data class State(
        val members: List<Member>,
        val channelName: String,
        val channelMuted: Boolean,
        val shouldExpandMembers: Boolean?,
        val membersToShowCount: Int,
        val isCurrentUserOwnerOrAdmin: Boolean,
    )

    sealed class Action {
        data class NameChanged(val name: String) : Action()
        data class MemberClicked(val member: Member) : Action()
        object MembersSeparatorClicked : Action()
        data class MuteChannelClicked(val isEnabled: Boolean) : Action()
        data class ChannelMutesUpdated(val channelMutes: List<ChannelMute>) : Action()
        object LeaveChannelClicked : Action()
    }

    sealed class UiEvent {
        data class ShowMemberOptions(val member: Member, val channelName: String) : UiEvent()
        object RedirectToHome : UiEvent()
    }

    sealed class ErrorEvent {
        object ChangeGroupNameError : ErrorEvent()
        object MuteChannelError : ErrorEvent()
        object LeaveChannelError : ErrorEvent()
    }

    companion object {
        const val COLLAPSED_MEMBERS_COUNT = 5

        private val INITIAL_STATE = State(
            members = emptyList(),
            channelName = "",
            channelMuted = false,
            shouldExpandMembers = null,
            membersToShowCount = 0,
            false,
        )
    }
}
