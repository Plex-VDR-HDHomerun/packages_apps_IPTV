/*
 * Copyright 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.iptv.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContentRating;
import android.media.tv.TvContract;
import android.media.tv.TvContract.Channels;
import android.media.tv.TvContract.Programs;
import android.net.Uri;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.Pair;
import android.util.SparseArray;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.android.iptv.domain.Channel;
import com.android.iptv.domain.PlaybackInfo;
import com.android.iptv.domain.Program;
import com.android.iptv.xmltv.XmlTvParser;

/**
 * Static helper methods for working with {@link TvContract}.
 */
public class TvContractUtil {
    private static final String TAG = "TvContractUtils";
    private static final boolean DEBUG = true;

    private static final SparseArray<String> VIDEO_HEIGHT_TO_FORMAT_MAP = new SparseArray<>();

    static {
        VIDEO_HEIGHT_TO_FORMAT_MAP.put(480, Channels.VIDEO_FORMAT_480P);
        VIDEO_HEIGHT_TO_FORMAT_MAP.put(576, Channels.VIDEO_FORMAT_576P);
        VIDEO_HEIGHT_TO_FORMAT_MAP.put(720, Channels.VIDEO_FORMAT_720P);
        VIDEO_HEIGHT_TO_FORMAT_MAP.put(1080, Channels.VIDEO_FORMAT_1080P);
        VIDEO_HEIGHT_TO_FORMAT_MAP.put(2160, Channels.VIDEO_FORMAT_2160P);
        VIDEO_HEIGHT_TO_FORMAT_MAP.put(4320, Channels.VIDEO_FORMAT_4320P);
    }

    private TvContractUtil() {}

    public static void updateChannels(
            Context context, String inputId, List<XmlTvParser.XmlTvChannel> channels) {
        // Create a map from original network ID to channel row ID for existing channels.
        SparseArray<Long> mExistingChannelsMap = new SparseArray<>();
        Uri channelsUri = TvContract.buildChannelsUriForInput(inputId);
        String[] projection = {Channels._ID, Channels.COLUMN_ORIGINAL_NETWORK_ID};
        Cursor cursor = null;
        ContentResolver resolver = context.getContentResolver();
        try {
            cursor = resolver.query(channelsUri, projection, null, null, null);
            while (cursor != null && cursor.moveToNext()) {
                long rowId = cursor.getLong(0);
                int originalNetworkId = cursor.getInt(1);
                mExistingChannelsMap.put(originalNetworkId, rowId);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        // If a channel exists, update it. If not, insert a new one.
        ContentValues values = new ContentValues();
        values.put(Channels.COLUMN_INPUT_ID, inputId);
        Map<Uri, String> logos = new HashMap<>();
        for (XmlTvParser.XmlTvChannel channel : channels) {
            values.put(Channels.COLUMN_DISPLAY_NUMBER, channel.displayNumber);
            values.put(Channels.COLUMN_DISPLAY_NAME, channel.displayName);
            values.put(Channels.COLUMN_ORIGINAL_NETWORK_ID, channel.originalNetworkId);
            values.put(Channels.COLUMN_TRANSPORT_STREAM_ID, channel.transportStreamId);
            values.put(Channels.COLUMN_SERVICE_ID, channel.serviceId);
            values.put(Channels.COLUMN_INTERNAL_PROVIDER_DATA, channel.url);
            Long rowId = mExistingChannelsMap.get(channel.originalNetworkId);
            Uri uri;
            if (rowId == null) {
                uri = resolver.insert(Channels.CONTENT_URI, values);
            } else {
                uri = TvContract.buildChannelUri(rowId);
                resolver.update(uri, values, null, null);
                mExistingChannelsMap.remove(channel.originalNetworkId);
            }
            if (!TextUtils.isEmpty(channel.icon.src)) {
                logos.put(TvContract.buildChannelLogoUri(uri), channel.icon.src);
            }
        }
        if (!logos.isEmpty()) {
            new InsertLogosTask(context).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, logos);
        }

        // Deletes channels which don't exist in the new feed.
        int size = mExistingChannelsMap.size();
        for (int i = 0; i < size; i++) {
            Long rowId = mExistingChannelsMap.valueAt(i);
            resolver.delete(TvContract.buildChannelUri(rowId), null, null);
        }
    }

    private static String getVideoFormat(int videoHeight) {
        return VIDEO_HEIGHT_TO_FORMAT_MAP.get(videoHeight);
    }

    public static LongSparseArray<XmlTvParser.XmlTvChannel> buildChannelMap(
            ContentResolver resolver, String inputId, List<XmlTvParser.XmlTvChannel> channels) {
        Uri uri = TvContract.buildChannelsUriForInput(inputId);
        String[] projection = {
                Channels._ID,
                Channels.COLUMN_DISPLAY_NUMBER
        };

        LongSparseArray<XmlTvParser.XmlTvChannel> channelMap = new LongSparseArray<>();
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, projection, null, null, null);
            if (cursor == null || cursor.getCount() == 0) {
                return null;
            }

            while (cursor.moveToNext()) {
                long channelId = cursor.getLong(0);
                String channelNumber = cursor.getString(1);
                channelMap.put(channelId, getChannelByNumber(channelNumber, channels));
            }
        } catch (Exception e) {
            Log.d(TAG, "Content provider query: " + e.getStackTrace());
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return channelMap;
    }

    public static Channel getChannel(ContentResolver resolver, Uri channelUri) {
        Cursor cursor = null;
        try {
            // TvProvider returns programs chronological order by default.
            cursor = resolver.query(channelUri, null, null, null, null);
            if (cursor == null || cursor.getCount() == 0) {
                return null;
            }
            if (cursor.moveToNext()) {
                return Channel.fromCursor(cursor);
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to get channel for " + channelUri, e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    public static List<Program> getPrograms(ContentResolver resolver, Uri channelUri) {
        Uri uri = TvContract.buildProgramsUriForChannel(channelUri);
        Cursor cursor = null;
        List<Program> programs = new ArrayList<>();
        try {
            // TvProvider returns programs chronological order by default.
            cursor = resolver.query(uri, null, null, null, null);
            if (cursor == null || cursor.getCount() == 0) {
                return programs;
            }
            while (cursor.moveToNext()) {
                programs.add(Program.fromCursor(cursor));
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to get programs for " + channelUri, e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return programs;
    }

    public static List<PlaybackInfo> getProgramPlaybackInfo(
            ContentResolver resolver, Uri channelUri, long startTimeMs, long endTimeMs,
            int maxProgramInReturn) {
        Uri uri = TvContract.buildProgramsUriForChannel(channelUri, startTimeMs,
                endTimeMs);
        String[] projection = { Programs.COLUMN_START_TIME_UTC_MILLIS,
                Programs.COLUMN_END_TIME_UTC_MILLIS,
                Programs.COLUMN_CONTENT_RATING,
                Programs.COLUMN_INTERNAL_PROVIDER_DATA,
                Programs.COLUMN_CANONICAL_GENRE };
        Cursor cursor = null;
        List<PlaybackInfo> list = new ArrayList<>();
        try {
            cursor = resolver.query(uri, projection, null, null, null);
            while (cursor != null && cursor.moveToNext()) {
                long startMs = cursor.getLong(0);
                long endMs = cursor.getLong(1);
                TvContentRating[] ratings = stringToContentRatings(cursor.getString(2));
                Pair<Integer, String> values = parseInternalProviderData(cursor.getString(3));
                int videoType = values.first;
                String videoUrl = values.second;
                list.add(new PlaybackInfo(startMs, endMs, videoUrl, videoType, ratings));
                if (list.size() > maxProgramInReturn) {
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get program playback info from TvProvider.", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return list;
    }

    public static String convertVideoInfoToInternalProviderData(int videotype, String videoUrl) {
        return videotype + "," + videoUrl;
    }

    public static Pair<Integer, String> parseInternalProviderData(String internalData) {
        String[] values = internalData.split(",", 2);
        if (values.length != 2) {
            throw new IllegalArgumentException(internalData);
        }
        return new Pair<>(Integer.parseInt(values[0]), values[1]);
    }

    public static void insertUrl(Context context, Uri contentUri, URL sourceUrl) {
        if (DEBUG) {
            Log.d(TAG, "Inserting " + sourceUrl + " to " + contentUri);
        }
        InputStream is = null;
        OutputStream os = null;
        try {
            is = sourceUrl.openStream();
            os = context.getContentResolver().openOutputStream(contentUri);
            copy(is, os);
        } catch (IOException ioe) {
            Log.e(TAG, "Failed to write " + sourceUrl + "  to " + contentUri, ioe);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    // Ignore exception.
                }
            }
            if (os != null) {
                try {
                    os.close();
                } catch (IOException e) {
                    // Ignore exception.
                }
            }
        }
    }

    public static void copy(InputStream is, OutputStream os) throws IOException {
        byte[] buffer = new byte[1024];
        int len;
        while ((len = is.read(buffer)) != -1) {
            os.write(buffer, 0, len);
        }
    }

    public static TvContentRating[] stringToContentRatings(String commaSeparatedRatings) {
        if (TextUtils.isEmpty(commaSeparatedRatings)) {
            return null;
        }
        String[] ratings = commaSeparatedRatings.split("\\s*,\\s*");
        TvContentRating[] contentRatings = new TvContentRating[ratings.length];
        for (int i = 0; i < contentRatings.length; ++i) {
            contentRatings[i] = TvContentRating.unflattenFromString(ratings[i]);
        }
        return contentRatings;
    }

    public static String contentRatingsToString(TvContentRating[] contentRatings) {
        if (contentRatings == null || contentRatings.length == 0) {
            return null;
        }
        final String DELIMITER = ",";
        StringBuilder ratings = new StringBuilder(contentRatings[0].flattenToString());
        for (int i = 1; i < contentRatings.length; ++i) {
            ratings.append(DELIMITER);
            ratings.append(contentRatings[i].flattenToString());
        }
        return ratings.toString();
    }

    private static XmlTvParser.XmlTvChannel getChannelByNumber(String channelNumber,
            List<XmlTvParser.XmlTvChannel> channels) {
        for (XmlTvParser.XmlTvChannel channel : channels) {
            if (channelNumber.equals(channel.displayNumber)) {
                return channel;
            }
        }
        throw new IllegalArgumentException("Unknown channel: " + channelNumber);
    }

    public static class InsertLogosTask extends AsyncTask<Map<Uri, String>, Void, Void> {
        private final Context context;

        InsertLogosTask(Context context) {
            this.context = context;
        }

        @Override
        public Void doInBackground(Map<Uri, String>... logosList) {
            for (Map<Uri, String> logos : logosList) {
                for (Uri uri : logos.keySet()) {
                    try {
                        insertUrl(context, uri, new URL(logos.get(uri)));
                    } catch (MalformedURLException e) {
                        Log.e(TAG, "Can't load " + logos.get(uri), e);
                    }
                }
            }
            return null;
        }
    }
}
