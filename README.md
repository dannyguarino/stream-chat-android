# stream-chat-android

[![Build Status](https://travis-ci.com/GetStream/stream-chat-android.svg?branch=master)](https://travis-ci.com/GetStream/stream-chat-android) ![version](https://jitpack.io/v/GetStream/stream-chat-android.svg) [![Component Reference](https://img.shields.io/badge/docs-component%20reference-blue.svg)](https://getstream.github.io/stream-chat-android/)

[stream-chat-android](https://github.com/GetStream/stream-chat-android) is the official Android SDK for [Stream Chat](https://getstream.io/chat), a service for building chat applications.

You can sign up for a Stream account at [https://getstream.io/chat/get_started/](https://getstream.io/chat/get_started/).
This library includes both a low level chat SDK and a set of reusable UI components.
Most users start out with the UI components, and fall back to the lower level API when they want to customize things.




## Installation

- **Step 1** Add repository into root build.gradle

~~~gradle
allprojects {
    repositories {
    ...
    maven {
        url 'https://jitpack.io' }
    }
}
~~~

- **Step 2** Add library dependency into app build.gradle

> See the jitpack badge above for the latest version number

~~~gradle
android {
    ...
    dataBinding {
        enabled = true
    }
	
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation 'com.github.getstream:stream-chat-android:<latest-version>'
}
~~~

## UI Components

### ChannelList

The ChannelListView shows a list of channel previews.
Typically it will show an unread/read state, the last message and who is participating in the conversation.

The easiest way to render a ChannelList is to add it to your layout:

```
<com.getstream.sdk.chat.view.ChannelListView android:id="@+id/channelList"
    android:layout_width="match_parent"
    android:layout_height="0dp"
    android:layout_marginBottom="10dp"
    app:layout_constraintTop_toTopOf="parent"
    app:layout_constraintBottom_toBottomOf="parent"
     />
```

And in activity do something like this:

```
package io.getstream.chat.example;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;
import androidx.lifecycle.ViewModelProviders;

import com.getstream.sdk.chat.StreamChat;
import com.getstream.sdk.chat.adapter.ChannelListItemAdapter;
import com.getstream.sdk.chat.adapter.ChannelListItemViewHolder;
import com.getstream.sdk.chat.enums.FilterObject;
import com.getstream.sdk.chat.rest.User;
import com.getstream.sdk.chat.rest.core.Client;
import com.getstream.sdk.chat.viewmodel.ChannelListViewModel;

import java.util.HashMap;

import io.getstream.chat.example.databinding.ActivityMainBinding;

import static com.getstream.sdk.chat.enums.Filters.in;


/**
 * This activity shows a list of channels
 */
public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_CHANNEL_TYPE = "io.getstream.chat.example.CHANNEL_TYPE";
    public static final String EXTRA_CHANNEL_ID = "io.getstream.chat.example.CHANNEL_ID";
    final String USER_ID = "broken-waterfall-5";
    // User token is typically provided by your server when the user authenticates
    final String USER_TOKEN = "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJ1c2VyX2lkIjoiYnJva2VuLXdhdGVyZmFsbC01In0.d1xKTlD_D0G-VsBoDBNbaLjO-2XWNA8rlTm4ru4sMHg";
    private ChannelListViewModel viewModel;

    // establish a websocket connection to stream
    protected Client configureStreamClient() {
        Client client = StreamChat.getInstance(this.getApplication());
        HashMap<String, Object> extraData = new HashMap<>();
        extraData.put("name", "Broken waterfall");
        extraData.put("image", "https://bit.ly/2u9Vc0r");
        User user = new User(USER_ID, extraData);
        client.setUser(user, USER_TOKEN);

        return client;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // setup the client
        Client client = configureStreamClient();

        // we're using data binding in this example
        ActivityMainBinding binding =
                DataBindingUtil.setContentView(this, R.layout.activity_main);

        // Specify the current activity as the lifecycle owner.
        binding.setLifecycleOwner(this);

        // most the business logic for chat is handled in the ChannelListViewModel view model
        viewModel = ViewModelProviders.of(this).get(ChannelListViewModel.class);
        // set the viewModel data for the activity_main.xml layout
        binding.setViewModel(viewModel);

        ChannelListItemAdapter adapter = new ChannelListItemAdapter(this);
        adapter.setCustomViewHolder(ChannelListItemViewHolder.class);
        binding.channelList.setViewModel(viewModel, this, adapter);

        // query all channels where the current user is a member
        // FilterObject filter = in("members", USER_ID);
        FilterObject filter = in("type", "messaging");
        viewModel.setChannelFilter(filter);

        // setup an onclick listener to capture clicks to the user profile or channel
        MainActivity parent = this;
        binding.channelList.setOnChannelClickListener(channel -> {
            // open the channel activity
            Intent intent = new Intent(parent, ChannelActivity.class);
            intent.putExtra(EXTRA_CHANNEL_TYPE, channel.getType());
            intent.putExtra(EXTRA_CHANNEL_ID, channel.getId());
            startActivity(intent);
        });
        binding.channelList.setOnUserClickListener(user -> {
            // TODO: open your user profile
        });

    }
}
```

#### Listeners

The following listeners can be set

* setOnChannelClickListener
* setOnLongClickListener
* setOnUserClickListener

#### Styling using Attributes

The following attributes are available:

```
<com.getstream.sdk.chat.view.ChannelListView android:id="@+id/channelList"
    android:layout_width="match_parent"
    android:layout_height="0dp"
    android:layout_marginBottom="10dp"
    app:layout_constraintTop_toTopOf="parent"
    app:layout_constraintBottom_toBottomOf="parent"
    stream:avatarWidth="40dp"
    stream:avatarHeight="40dp"
    stream:avatarInitialsTextSize="16sp"
    stream:avatarInitialsTextColor="#000"
    stream:avatarInitialsTextStyle="bold"
    stream:dateTextColor="#858585"
    stream:dateTextSize="11sp"
    stream:titleTextColor="#000"
    stream:titleTextSize="16sp"
    stream:titleTextStyleChannel="bold"
    stream:unreadTitleTextColor="#000"
    stream:unreadTitleTextStyle="bold"
    stream:messageTextColor="#858585"
    stream:messageTextSize="16sp"
    stream:messageTextStyle="normal"
    stream:unreadMessageTextColor="#000"
    stream:unreadMessageTextStyle="bold"
    stream:channelPreviewLayout="@layout/list_item_channel_custom"
     />
```

#### Changing the layout

If you need to make a bigger change you can swap the layout for the channel previews.

```
<com.getstream.sdk.chat.view.ChannelListView android:id="@+id/channelList"
    android:layout_width="match_parent"
    android:layout_height="0dp"
    android:layout_marginBottom="10dp"
    app:layout_constraintTop_toTopOf="parent"
    app:layout_constraintBottom_toBottomOf="parent"
    stream:channelPreviewLayout="@layout/list_item_channel_custom"
     />
```

That only works for simple changes where you don't change the IDs of views, or their types.
You can find the default layout and copy and paste it in **list_item_channel.xml**

#### Custom Viewholder

If you need full control over the styling for the channel preview you can overwrite the view holder.

```
ChannelListItemAdapter adapter = new ChannelListItemAdapter(this);
adapter.setCustomViewHolder(MyCustomViewHolder.class);
binding.channelList.setViewModel(viewModel, this, adapter);
```

You'll typically want to extend either the `ChannelListItemViewHolder` or the `BaseChannelListItemViewHolder` class.

#### Client usage

Alternatively you can of course build your own ChannelListView using the low level client.

### Message List

The message list renders a list of messages. You can use it like this:

```
<com.getstream.sdk.chat.view.MessageListView
    android:id="@+id/messageList"
    android:layout_width="match_parent"
    android:layout_height="0dp"
    android:layout_marginBottom="10dp"
    android:background="#FFF"
    app:layout_constraintBottom_toTopOf="@+id/message_input"
    app:layout_constraintEnd_toStartOf="@+id/message_input"
    app:layout_constraintStart_toEndOf="@+id/channelHeader"
    app:layout_constraintTop_toBottomOf="@+id/channelHeader"
    stream:reactionDlgEmojiSize="50dp"
    stream:reactionDlgbgColor="#001DC4"
    stream:showUsersReactionDlg="true"
    stream:userAvatarTextColor="#FFF"
    stream:userAvatarTextStyle="bold"
    stream:userReadStateAvatarHeight="15dp"
    stream:userReadStateAvatarWidth="15dp"
    stream:userReadStateTextColor="#FFF"
    stream:userReadStateTextStyle="bold"
    stream:userRreadStateTextSize="9sp" />
```

And here's a full example of an activity that renders a message list, channel header and message input

```
package io.getstream.chat.example;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;
import androidx.lifecycle.ViewModelProviders;

import com.getstream.sdk.chat.StreamChat;
import com.getstream.sdk.chat.model.Attachment;
import com.getstream.sdk.chat.model.Channel;
import com.getstream.sdk.chat.model.ModelType;
import com.getstream.sdk.chat.rest.Message;
import com.getstream.sdk.chat.rest.core.Client;
import com.getstream.sdk.chat.utils.Constant;
import com.getstream.sdk.chat.utils.PermissionChecker;
import com.getstream.sdk.chat.utils.frescoimageviewer.ImageViewer;
import com.getstream.sdk.chat.view.MessageInputView;
import com.getstream.sdk.chat.view.MessageListView;
import com.getstream.sdk.chat.view.activity.AttachmentActivity;
import com.getstream.sdk.chat.viewmodel.ChannelViewModel;
import com.getstream.sdk.chat.viewmodel.ChannelViewModelFactory;

import java.util.ArrayList;
import java.util.List;

import io.getstream.chat.example.databinding.ActivityChannelBinding;

/**
 * Show the messages for a channel
 */
public class ChannelActivity extends AppCompatActivity
        implements MessageListView.MessageClickListener,
        MessageListView.AttachmentClickListener,
        MessageInputView.OpenCameraViewListener {

    final String TAG = ChannelActivity.class.getSimpleName();

    private ChannelViewModel viewModel;
    private ActivityChannelBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // receive the intent and create a channel object
        Intent intent = getIntent();
        String channelType = intent.getStringExtra(MainActivity.EXTRA_CHANNEL_TYPE);
        String channelID = intent.getStringExtra(MainActivity.EXTRA_CHANNEL_ID);
        Client client = StreamChat.getInstance(getApplication());


        // we're using data binding in this example
        binding =
                DataBindingUtil.setContentView(this, R.layout.activity_channel);
        // most the business logic of the chat is handled in the ChannelViewModel view model
        binding.setLifecycleOwner(this);

        Channel channel = client.getChannelByCid(channelType + ":" + channelID);
        if (channel == null)
            channel = client.channel(channelType, channelID);
        viewModel = ViewModelProviders.of(this,
                new ChannelViewModelFactory(this.getApplication(), channel)
        ).get(ChannelViewModel.class);

        // connect the view model
        binding.channelHeader.setViewModel(viewModel, this);
        binding.channelHeader.setOnBackClickListener(v -> finish());

        MyMessageViewHolderFactory factory = new MyMessageViewHolderFactory();
        binding.messageList.setViewHolderFactory(factory);
//        binding.messageList.setMessageClickListener(this);
        binding.messageList.setAttachmentClickListener(this);

        binding.messageInput.setViewModel(viewModel, this);
        binding.messageList.setViewModel(viewModel, this);
        binding.messageInput.setOpenCameraViewListener(this);
        // set the viewModel data for the activity_channel.xml layout
        binding.setViewModel(viewModel);
        // Permission Check
        PermissionChecker.permissionCheck(this, null);
    }

    @Override
    public void onBackPressed() {
        viewModel.removeEventHandler();
        super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == Constant.PERMISSIONS_REQUEST) {
            boolean granted = true;
            for (int grantResult : grantResults)
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            if (!granted) PermissionChecker.showRationalDialog(this, null);
        }
    }

    @Override
    public void openCameraView(Intent intent, int REQUEST_CODE) {
        startActivityForResult(intent, REQUEST_CODE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        binding.messageInput.progressCapturedMedia(requestCode, resultCode, data);
    }

    @Override
    public void onMessageClick(Message message, int position) {
        Log.i(TAG, "message was clicked");
        showReactionDialog(message);
    }

    @Override
    public void onAttachmentClick(Message message, Attachment attachment) {
        Log.i(TAG, "attachment was clicked");
        // Image

        if (attachment.getType().equals(ModelType.attach_image)) {
            List<String> imageUrls = new ArrayList<>();
            for (Attachment a : message.getAttachments()) {
                imageUrls.add(a.getImageURL());
            }

            int position = message.getAttachments().indexOf(attachment);

            new ImageViewer.Builder<>(this, imageUrls)
                    .setStartPosition(position)
                    .show();
        } else {
            // Giphy, Video, Link, Product,...
            Intent mediaIntent = new Intent(this, AttachmentActivity.class);
            this.startActivity(mediaIntent);
        }

    }

    public void showReactionDialog(Message message) {

    }
}
```

#### Customizing the message list - Attributes

TODO document the attributes for styling the message list

#### Customizing the message list - BubbleHelper

Many messaging apps will have rather complex message bubbles. The layout typically changes based on the position of the message, if it's your or written by someone else, and if it has attachments. Here's an example of the default bubble list helper.

```
messageList.setBubbleHelper(new BubbleHelper() {
    @Override
    public Drawable getDrawableForMessage(Message message, Boolean mine, List<MessageViewHolderFactory.Position> positions) {
	if (mine) {
	    // if the size is 0 the attachment has the corner change
	    if (positions.contains(MessageViewHolderFactory.Position.TOP) && message.getAttachments().size() == 0) {
		return getResources().getDrawable(R.drawable.message_bubble_mine_top);
	    }
	    return style.getMessageBubbleDrawableMine();
	} else {
	    if (positions.contains(MessageViewHolderFactory.Position.TOP) && message.getAttachments().size() == 0) {
		return getResources().getDrawable(R.drawable.message_bubble_theirs_top);
	    }
	    return style.getMessageBubbleDrawableTheirs();
	}
    }

    @Override
    public Drawable getDrawableForAttachment(Message message, Boolean mine, List<MessageViewHolderFactory.Position> positions, Attachment attachment) {
	if (positions.contains(MessageViewHolderFactory.Position.TOP)) {
	    int attachmentPosition = message.getAttachments().indexOf(attachment);
	    if (attachmentPosition == 0) {
		return getResources().getDrawable(R.drawable.round_attach_media_incoming1);
	    }
	}
	return getResources().getDrawable(R.drawable.round_attach_media_incoming2);
    }
});
```

#### Customizing the message list - ViewHolderFactory

You can configure your own viewholder factory like this:

```
MyMessageViewHolderFactory factory = new MyMessageViewHolderFactory();
binding.messageList.setViewHolderFactory(factory);
```

This allows you to swap the layout file that's used for the typing indicator, the date seperator or the message. You can also implement your own view holder. It's common to implement your own message or attachment type and render it in a custom way.

```
public class MessageViewHolderFactory {
    private static int NOT_FOUND = 0;
    private static int DATE_SEPARATOR = 1;
    private static int MESSAGE = 2;
    private static int TYPING = 3;

    private static int GENERIC_ATTACHMENT = 1;
    private static int IMAGE_ATTACHMENT = 2;
    private static int VIDEO_ATTACHMENT = 3;
    private static int FILE_ATTACHMENT = 4;


    public enum Position {
        TOP, MIDDLE, BOTTOM
    }

    public int getEntityViewType(MessageListItem messageListItem, Boolean mine, List<Position> positions) {
        // typing
        // date
        // various message types
        MessageListItemAdapter.EntityType entityType = messageListItem.getType();
        if (entityType == MessageListItemAdapter.EntityType.DATE_SEPARATOR) {
            return DATE_SEPARATOR;
        } else if (entityType == MessageListItemAdapter.EntityType.MESSAGE) {
            return MESSAGE;
        } else if (entityType == MessageListItemAdapter.EntityType.TYPING) {
            return TYPING;
        }
        return NOT_FOUND;
    }

    public int getAttachmentViewType(Message message, Boolean mine, Position position, List<Attachment> attachments, Attachment attachment) {
        // video
        // image
        // link/card layout
        // custom attachment types
        String t = attachment.getType();
        if (t == null) {
            return GENERIC_ATTACHMENT;
        } else if (t.equals(ModelType.attach_video)) {
            return VIDEO_ATTACHMENT;
        } else if (t.equals(ModelType.attach_image)) {
            return IMAGE_ATTACHMENT;
        } else if (t.equals(ModelType.attach_file)) {
            return FILE_ATTACHMENT;
        } else {
            return GENERIC_ATTACHMENT;
        }

    }

    public BaseMessageListItemViewHolder createMessageViewHolder(MessageListItemAdapter adapter, ViewGroup parent,int viewType) {
        if (viewType == DATE_SEPARATOR) {
            DateSeparatorViewHolder holder = new DateSeparatorViewHolder(R.layout.list_item_date_separator, parent);
            holder.setStyle(adapter.getStyle());
            return holder;
        } else if (viewType == MESSAGE) {
            MessageListItemViewHolder holder = new MessageListItemViewHolder(R.layout.list_item_message, parent);
            holder.setViewHolderFactory(this);
            holder.setStyle(adapter.getStyle());
            return holder;

        } else if (viewType == TYPING) {
            TypingIndicatorViewHolder holder = new TypingIndicatorViewHolder(R.layout.list_item_type_indicator, parent);
            holder.setStyle(adapter.getStyle());
            return holder;
        } else {
            return null;
        }
    }

    public BaseAttachmentViewHolder createAttachmentViewHolder(AttachmentListItemAdapter adapter, ViewGroup parent, int viewType) {
        if (viewType == VIDEO_ATTACHMENT || viewType == IMAGE_ATTACHMENT) {
            AttachmentViewHolderMedia holder = new AttachmentViewHolderMedia(R.layout.list_item_attachment_video, parent);
            holder.setStyle(adapter.getStyle());
            return holder;
        } else if (viewType == FILE_ATTACHMENT) {
            AttachmentViewHolderFile holder = new AttachmentViewHolderFile(R.layout.list_item_attachment_file, parent);
            holder.setStyle(adapter.getStyle());
            return holder;
        } else {
            AttachmentViewHolder holder = new AttachmentViewHolder(R.layout.list_item_attachment, parent);
            holder.setStyle(adapter.getStyle());
            return holder;
        }

    }
}
```


## Getting started

- **Initialize Client**

 Java
~~~java
StreamChat streamChat = new StreamChat("API KEY");
~~~

 Kotlin
~~~
val streamChat = StreamChat(API_KEY)
~~~

- **Initialize User**

 Java
~~~java
User user = new User("USER ID", EXTRA_DATA); // EXTRA_DATA : HashMap
~~~

 Kotlin
~~~
val user = User(USER_ID, extraData)
~~~

- **Set User**

 Java
~~~java
streamChat.setUser(user, "USER TOKEN");
~~~

 Kotlin
~~~
streamChat.setUser(user, "USER TOKEN")
~~~

- **Adding Single Conversation Screen**

Adding chat is simple as the library comes with a built-in **ChatActivity** of library class which loads messages for a specified activeChannel using the APIs and renders its content.

  1. Set specified **activeChannel** object with activeChannel Id, activeChannel name and activeChannel image.
  2. Set  specified **activeChannel** to **streamChat**.
  3. Navigate to **ChatActivity** 

 Java
~~~java
// Setting Channel
Channel activeChannel = new Channel("CHANNEL TYPE", "CHANNEL ID", EXTRA_DATA); // EXTRA_DATA : HashMap
streamChat.setChannel(activeChannel);
 
// Start ChatActivity
Intent i = new Intent(this, ChatActivity.class);
startActivity(i);
~~~

 Kotlin 
~~~kotlin
// Setting Channel
val activeChannel = Channel("CHANNEL TYPE", "CHANNEL ID", EXTRA_DATA) // EXTRA_DATA : HashMap
streamChat.activeChannel = activeChannel

// Start ChatActivity
val i = Intent(this, ChatActivity::class.java)
startActivity(i)
~~~

- **Multiple conversations**

We can add **ChannelListFragment** of library in any Activity or Fragment directly.

 Xml layout

~~~xml
<FrameLayout
    android:fitsSystemWindows="true"
    android:id="@+id/container"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />	
~~~

 Java
~~~java
ChannelListFragment fragment = new ChannelListFragment(); 
fragment.containerResId = R.id.container;  
FragmentManager fragmentManager = getSupportFragmentManager();
FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
fragmentTransaction.replace(R.id.container, fragment);
fragmentTransaction.addToBackStack(null);
fragmentTransaction.commit();
~~~

 Kotlin
~~~kotlin
val fragment = ChannelListFragment()
fragment.containerResId = R.id.container  
val fragmentManager = supportFragmentManager
val fragmentTransaction = fragmentManager.beginTransaction()
fragmentTransaction.replace(R.id.container, fragment)
fragmentTransaction.addToBackStack(null)
fragmentTransaction.commit()
~~~

## Documentation

TODO - Java Chat Tutorial page
[Official API Docs](https://getstream.io/chat/docs)

## In progress/ not supported yet

- Threads
- Slash commands

## Supported features

- A group chat
- Channel list
- Reactions
- A link preview
- Attach images, videos or files
- Edit a message
- Typing events
- Read events
- Notifications
- Opening a link in the internal browser
- Image gallery
- Supporting Gifs
- Light/Dark styles
- Style customization
- UI customization

## Getting started


TODO: https://getstream.io/chat/docs/#introduction but with Android code examples
