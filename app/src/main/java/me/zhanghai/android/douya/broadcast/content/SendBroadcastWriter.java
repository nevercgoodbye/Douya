/*
 * Copyright (c) 2018 Zhang Hai <Dreaming.in.Code.ZH@Gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.douya.broadcast.content;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

import me.zhanghai.android.douya.R;
import me.zhanghai.android.douya.app.Notifications;
import me.zhanghai.android.douya.content.ResourceWriter;
import me.zhanghai.android.douya.content.ResourceWriterManager;
import me.zhanghai.android.douya.eventbus.BroadcastSendErrorEvent;
import me.zhanghai.android.douya.eventbus.BroadcastSentEvent;
import me.zhanghai.android.douya.eventbus.BroadcastWriteStartedEvent;
import me.zhanghai.android.douya.eventbus.EventBusUtils;
import me.zhanghai.android.douya.network.api.ApiError;
import me.zhanghai.android.douya.network.api.ApiRequest;
import me.zhanghai.android.douya.network.api.ApiService;
import me.zhanghai.android.douya.network.api.info.frodo.Broadcast;
import me.zhanghai.android.douya.network.api.info.frodo.UploadedImage;
import me.zhanghai.android.douya.util.CollectionUtils;
import me.zhanghai.android.douya.util.LogUtils;
import me.zhanghai.android.douya.util.ToastUtils;

class SendBroadcastWriter extends ResourceWriter<SendBroadcastWriter> {

    private static int sNextId = 1;

    private long mId;

    private String mText;
    private List<Uri> mImageUris;
    private String mLinkTitle;
    private String mLinkUrl;

    private boolean mHasImages;
    private List<String> mUploadedImageUrls;

    private ApiRequest<?> mRequest;

    SendBroadcastWriter(String text, List<Uri> imageUris, String linkTitle, String linkUrl,
                        ResourceWriterManager<SendBroadcastWriter> manager) {
        super(manager);

        mId = sNextId;
        ++sNextId;

        mText = text;
        mImageUris = imageUris;
        mLinkTitle = linkTitle;
        mLinkUrl = linkUrl;

        mHasImages = !CollectionUtils.isEmpty(mImageUris);
        if (mHasImages) {
            mUploadedImageUrls = new ArrayList<>();
        }
    }

    public long getId() {
        return mId;
    }

    public String getText() {
        return mText;
    }

    public List<Uri> getImageUris() {
        return mImageUris;
    }

    public String getLinkTitle() {
        return mLinkTitle;
    }

    public String getLinkUrl() {
        return mLinkUrl;
    }

    @Override
    public void onStart() {
        if (mHasImages) {
            sendWithImages();
        } else {
            sendSimple();
        }
        EventBusUtils.postAsync(new BroadcastWriteStartedEvent(mId, this));
    }

    @Override
    public void onDestroy() {
        if (mRequest != null) {
            mRequest.cancel();
            mRequest = null;
        }
    }

    private void sendSimple() {
        ApiRequest<Broadcast> request = ApiService.getInstance().sendBroadcast(mText, null,
                mLinkTitle, mLinkUrl);
        request.enqueue(new ApiRequest.Callback<Broadcast>() {
            @Override
            public void onResponse(Broadcast response) {
                onSuccessSimple(response);
            }
            @Override
            public void onErrorResponse(ApiError error) {
                onErrorSimple(error);
            }
        });
        mRequest = request;
    }

    private void sendWithImages() {
        if (mUploadedImageUrls.size() < mImageUris.size()) {
            Context context = getContext();
            String notificationText = context.getString(
                    R.string.broadcast_sending_notification_text_uploading_images_format,
                    mUploadedImageUrls.size() + 1, mImageUris.size());
            startForeground(notificationText);
            ApiRequest<UploadedImage> request = ApiService.getInstance().uploadBroadcastImage(
                    mImageUris.get(mUploadedImageUrls.size()), context);
            request.enqueue(new ApiRequest.Callback<UploadedImage>() {
                @Override
                public void onResponse(UploadedImage response) {
                    onImageUploadSuccess(response);
                }
                @Override
                public void onErrorResponse(ApiError error) {
                    onErrorWithImages(error);
                }
            });
            mRequest = request;
        } else {
            startForeground(getContext().getString(
                    R.string.broadcast_sending_notification_text_sending));
            ApiRequest<Broadcast> request = ApiService.getInstance().sendBroadcast(mText,
                    mUploadedImageUrls, mLinkTitle, mLinkUrl);
            request.enqueue(new ApiRequest.Callback<Broadcast>() {
                @Override
                public void onResponse(Broadcast response) {
                    onSuccessWithImages(response);
                }
                @Override
                public void onErrorResponse(ApiError error) {
                    onErrorWithImages(error);
                }
            });
            mRequest = request;
        }
    }

    private void startForeground(String contentText) {
        if (mUploadedImageUrls.isEmpty()) {
            createNotificationChannel();
        }
        Context context = getContext();
        Notification notification = new NotificationCompat.Builder(context,
                Notifications.Channels.SEND_BROADCAST.ID)
                .setColor(ContextCompat.getColor(context, R.color.douya_primary))
                .setSmallIcon(R.drawable.notification_icon)
                .setContentTitle(context.getString(R.string.broadcast_sending_notification_title))
                .setContentText(contentText)
                .setTicker(contentText)
                .setOngoing(true)
                .build();
        getService().startForeground(Notifications.Ids.SENDING_BROADCAST, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        Context context = getContext();
        String channelName = context.getString(
                Notifications.Channels.SEND_BROADCAST.NAME_RES);
        @SuppressLint("WrongConstant")
        NotificationChannel channel = new NotificationChannel(
                Notifications.Channels.SEND_BROADCAST.ID, channelName,
                Notifications.Channels.SEND_BROADCAST.IMPORTANCE);
        String channelDescription = context.getString(
                R.string.notification_channel_send_broadcast_description);
        channel.setDescription(channelDescription);
        NotificationManager notificationManager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.createNotificationChannel(channel);
    }

    @Override
    protected void stopSelf() {

        if (mHasImages) {
            getService().stopForeground(true);
        }

        super.stopSelf();
    }

    private void onSuccessSimple(Broadcast broadcast) {

        ToastUtils.show(R.string.broadcast_send_successful, getContext());

        EventBusUtils.postAsync(new BroadcastSentEvent(mId, broadcast, this));

        stopSelf();
    }

    private void onErrorSimple(ApiError error) {

        LogUtils.e(error.toString());
        Context context = getContext();
        ToastUtils.show(context.getString(R.string.broadcast_send_failed_format,
                ApiError.getErrorString(error, context)), context);

        EventBusUtils.postAsync(new BroadcastSendErrorEvent(mId, this));

        stopSelf();
    }

    private void onImageUploadSuccess(UploadedImage uploadedImage) {
        mUploadedImageUrls.add(uploadedImage.url);
        sendWithImages();
    }

    private void onSuccessWithImages(Broadcast broadcast) {
        onSuccessSimple(broadcast);
    }

    private void onErrorWithImages(ApiError error) {
        // TODO: Post notification.
        onErrorSimple(error);
    }
}
