package io.getstream.chat.android.livedata.usecase

import io.getstream.chat.android.client.api.models.QuerySort
import io.getstream.chat.android.client.models.Channel
import io.getstream.chat.android.client.utils.FilterObject
import io.getstream.chat.android.livedata.ChatDomainImpl
import io.getstream.chat.android.livedata.utils.Call2
import io.getstream.chat.android.livedata.utils.CallImpl2

interface QueryChannelsLoadMore {
    operator fun invoke(filter: FilterObject, sort: QuerySort?, limit: Int = 30, messageLimit: Int = 10): Call2<List<Channel>>
}

class QueryChannelsLoadMoreImpl(var domainImpl: ChatDomainImpl) : QueryChannelsLoadMore {
    override operator fun invoke(filter: FilterObject, sort: QuerySort?, limit: Int, messageLimit: Int): Call2<List<Channel>> {
        var runnable = suspend {
            val queryChannelsController = domainImpl.queryChannels(filter, sort)
            queryChannelsController.loadMore(limit, messageLimit)
        }
        return CallImpl2<List<Channel>>(
            runnable,
            domainImpl.scope
        )
    }
}
