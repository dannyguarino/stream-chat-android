package io.getstream.chat.android.livedata.usecase

import io.getstream.chat.android.livedata.ChatDomainImpl
import io.getstream.chat.android.livedata.utils.Call2
import io.getstream.chat.android.livedata.utils.CallImpl2
import java.security.InvalidParameterException

interface ShowChannel {
    operator fun invoke(cid: String): Call2<Unit>
}

class ShowChannelImpl(var domainImpl: ChatDomainImpl) : ShowChannel {
    override operator fun invoke(cid: String): Call2<Unit> {
        if (cid.isEmpty()) {
            throw InvalidParameterException("cid cant be empty")
        }
        val channelController = domainImpl.channel(cid)

        var runnable = suspend {
            channelController.show()
        }
        return CallImpl2<Unit>(
            runnable,
            channelController.scope
        )
    }
}
