package io.getstream.chat.android.offline.usecase

import androidx.annotation.CheckResult
import io.getstream.chat.android.client.call.Call
import io.getstream.chat.android.client.call.CoroutineCall
import io.getstream.chat.android.client.utils.Result
import io.getstream.chat.android.offline.ChatDomainImpl
import kotlinx.coroutines.flow.StateFlow

@Deprecated(
    message = "Use ChatDomain::totalUnreadCount instead",
    level = DeprecationLevel.WARNING,
)
public interface GetTotalUnreadCount {
    /**
     * Returns the total unread messages count for the current user.
     * You might also be interested in GetUnreadChannelCount
     * Or ChannelController.unreadCount
     *
     * @see io.getstream.chat.android.offline.usecase.GetUnreadChannelCount
     * @see io.getstream.chat.android.offline.channel.ChannelController.unreadCount
     */
    @CheckResult
    public operator fun invoke(): Call<StateFlow<Int>>
}

internal class GetTotalUnreadCountImpl(private val domainImpl: ChatDomainImpl) : GetTotalUnreadCount {
    override operator fun invoke(): Call<StateFlow<Int>> {
        return CoroutineCall(domainImpl.scope) {
            Result(domainImpl.totalUnreadCount)
        }
    }
}
