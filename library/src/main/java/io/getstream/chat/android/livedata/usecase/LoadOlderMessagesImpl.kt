package io.getstream.chat.android.livedata.usecase

import io.getstream.chat.android.client.models.Channel
import io.getstream.chat.android.livedata.ChatDomainImpl
import io.getstream.chat.android.livedata.utils.Call2
import io.getstream.chat.android.livedata.utils.CallImpl2

interface LoadOlderMessages {
    operator fun invoke(cid: String, messageLimit: Int): Call2<Channel>
}

class LoadOlderMessagesImpl(var domainImpl: ChatDomainImpl) : LoadOlderMessages {
    override operator fun invoke(cid: String, messageLimit: Int): Call2<Channel> {
        val channelRepo = domainImpl.channel(cid)
        var runnable = suspend {
            channelRepo.loadOlderMessages(messageLimit)
        }
        return CallImpl2<Channel>(
            runnable,
            channelRepo.scope
        )
    }
}
