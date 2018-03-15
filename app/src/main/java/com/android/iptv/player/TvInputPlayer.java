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
package com.android.iptv.player;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.MediaCodec;
import android.media.tv.TvTrackInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;

import com.google.android.exoplayer.DefaultLoadControl;
import com.google.android.exoplayer.DummyTrackRenderer;
import com.google.android.exoplayer.ExoPlaybackException;
import com.google.android.exoplayer.ExoPlayer;
import com.google.android.exoplayer.ExoPlayerLibraryInfo;
import com.google.android.exoplayer.LoadControl;
import com.google.android.exoplayer.MediaCodecAudioTrackRenderer;
import com.google.android.exoplayer.MediaCodecTrackRenderer;
import com.google.android.exoplayer.MediaCodecUtil;
import com.google.android.exoplayer.MediaCodecVideoTrackRenderer;
import com.google.android.exoplayer.TrackRenderer;
import com.google.android.exoplayer.chunk.ChunkSampleSource;
import com.google.android.exoplayer.chunk.ChunkSource;
import com.google.android.exoplayer.chunk.Format;
import com.google.android.exoplayer.chunk.FormatEvaluator;
import com.google.android.exoplayer.chunk.VideoFormatSelectorUtil;
import com.google.android.exoplayer.dash.DashChunkSource;
import com.google.android.exoplayer.dash.DefaultDashTrackSelector;
import com.google.android.exoplayer.dash.mpd.AdaptationSet;
import com.google.android.exoplayer.dash.mpd.MediaPresentationDescription;
import com.google.android.exoplayer.dash.mpd.MediaPresentationDescriptionParser;
import com.google.android.exoplayer.dash.mpd.Period;
import com.google.android.exoplayer.dash.mpd.Representation;
import com.google.android.exoplayer.extractor.ExtractorSampleSource;
import com.google.android.exoplayer.hls.HlsChunkSource;
import com.google.android.exoplayer.hls.HlsMasterPlaylist;
import com.google.android.exoplayer.hls.HlsPlaylist;
import com.google.android.exoplayer.hls.HlsPlaylistParser;
import com.google.android.exoplayer.hls.HlsSampleSource;
import com.google.android.exoplayer.hls.Variant;
import com.google.android.exoplayer.text.Cue;
import com.google.android.exoplayer.text.TextRenderer;
import com.google.android.exoplayer.text.eia608.Eia608TrackRenderer;
import com.google.android.exoplayer.upstream.DataSource;
import com.google.android.exoplayer.upstream.DefaultAllocator;
import com.google.android.exoplayer.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer.upstream.DefaultUriDataSource;
import com.google.android.exoplayer.util.ManifestFetcher;
import com.google.android.exoplayer.util.MimeTypes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A wrapper around {@link ExoPlayer} that provides a higher level interface. Designed for
 * integration with {@link android.media.tv.TvInputService}.
 */
public class TvInputPlayer implements TextRenderer {

    public static final int SOURCE_TYPE_HTTP_PROGRESSIVE = 0;
    public static final int SOURCE_TYPE_HLS = 1;
    public static final int SOURCE_TYPE_MPEG_DASH = 2;

    private static final int RENDERER_COUNT = 3;
    private static final int MIN_BUFFER_MS = 1000;
    private static final int MIN_REBUFFER_MS = 5000;

    private static final int BUFFER_SEGMENTS = 300;
    private static final int BUFFER_SEGMENT_SIZE = 64 * 1024;
    private static final int VIDEO_BUFFER_SEGMENTS = 200;
    private static final int AUDIO_BUFFER_SEGMENTS = 60;
    private static final int LIVE_EDGE_LATENCY_MS = 30000;

    private static final int NO_TRACK_SELECTED = -1;

    private final Handler handler;
    private final ExoPlayer player;
    private TrackRenderer videoRenderer;
    private TrackRenderer audioRenderer;
    private TrackRenderer textRenderer;
    private final CopyOnWriteArrayList<Callback> callbacks;
    private float volume;
    private Surface surface;
    private Long pendingSeekPosition;
    private final TvTrackInfo[][] tvTracks = new TvTrackInfo[RENDERER_COUNT][];
    private final int[] selectedTvTracks = new int[RENDERER_COUNT];

    private final MediaCodecVideoTrackRenderer.EventListener videoRendererEventListener =
            new MediaCodecVideoTrackRenderer.EventListener() {
                @Override
                public void onDroppedFrames(int count, long elapsed) {
                    // Do nothing.
                }

                @Override
                public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {

                }

                @Override
                public void onDrawnToSurface(Surface surface) {
                    for(Callback callback : callbacks) {
                        callback.onDrawnToSurface(surface);
                    }
                }

                @Override
                public void onDecoderInitialized(String decoderName, long elapsedRealtimeMs, long initializationDurationMs) {

                }

                @Override
                public void onDecoderInitializationError(
                        MediaCodecTrackRenderer.DecoderInitializationException e) {
                    for(Callback callback : callbacks) {
                        callback.onPlayerError(new ExoPlaybackException(e));
                    }
                }

                @Override
                public void onCryptoError(MediaCodec.CryptoException e) {
                    for(Callback callback : callbacks) {
                        callback.onPlayerError(new ExoPlaybackException(e));
                    }
                }
            };

    public TvInputPlayer() {
        handler = new Handler();
        for (int i = 0; i < RENDERER_COUNT; ++i) {
            tvTracks[i] = new TvTrackInfo[0];
            selectedTvTracks[i] = NO_TRACK_SELECTED;
        }
        callbacks = new CopyOnWriteArrayList<>();
        player = ExoPlayer.Factory.newInstance(RENDERER_COUNT, MIN_BUFFER_MS, MIN_REBUFFER_MS);
        player.addListener(new ExoPlayer.Listener() {
            @Override
            public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                for (Callback callback : callbacks) {
                    callback.onPlayerStateChanged(playWhenReady, playbackState);
                }
                if (pendingSeekPosition != null && playbackState != ExoPlayer.STATE_IDLE
                        && playbackState != ExoPlayer.STATE_PREPARING) {
                    seekTo(pendingSeekPosition);
                    pendingSeekPosition = null;
                }
            }

            @Override
            public void onPlayWhenReadyCommitted() {
                for (Callback callback : callbacks) {
                    callback.onPlayWhenReadyCommitted();
                }
            }

            @Override
            public void onPlayerError(ExoPlaybackException e) {
                for (Callback callback : callbacks) {
                    callback.onPlayerError(e);
                }
            }
        });
    }

    @Override
    public void onCues(List<Cue> cues) {
        for (Callback callback : callbacks) {
            callback.onCues(cues);
        }
    }

    public void prepare(final Context context, final Uri originalUri, int sourceType) {

        final String userAgent = getUserAgent(context);
        final DefaultHttpDataSource dataSource = new DefaultHttpDataSource(userAgent, null);
        final Uri uri = processUriParameters(originalUri, dataSource);

        if (sourceType == SOURCE_TYPE_HTTP_PROGRESSIVE) {
            ExtractorSampleSource sampleSource =
                    new ExtractorSampleSource(uri, dataSource, new DefaultAllocator(BUFFER_SEGMENT_SIZE),
                            BUFFER_SEGMENTS * BUFFER_SEGMENT_SIZE);
            audioRenderer = new MediaCodecAudioTrackRenderer(sampleSource);
            videoRenderer = new MediaCodecVideoTrackRenderer(sampleSource,
                    MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT, 0, handler,
                    videoRendererEventListener, 50);
            textRenderer = new DummyTrackRenderer();
            prepareInternal();
        } else if (sourceType == SOURCE_TYPE_HLS) {
            HlsPlaylistParser parser = new HlsPlaylistParser();
            ManifestFetcher<HlsPlaylist> playlistFetcher =
                    new ManifestFetcher<>(uri.toString(), dataSource, parser);
            playlistFetcher.singleLoad(handler.getLooper(),
                    new ManifestFetcher.ManifestCallback<HlsPlaylist>() {
                        @Override
                        public void onSingleManifest(HlsPlaylist manifest) {
                            DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();

                            int[] variantIndices = null;
                            if (manifest instanceof HlsMasterPlaylist) {
                                HlsMasterPlaylist masterPlaylist = (HlsMasterPlaylist) manifest;

                                // Sort playlist from highest bitrate to lowest
                                ArrayList<Variant> variants = new ArrayList<>(masterPlaylist.variants);
                                Collections.sort(variants, new Comparator<Variant>() {
                                    @Override
                                    public int compare(Variant v1, Variant v2) {
                                        return Integer.compare(v2.format.bitrate, v1.format.bitrate);
                                    }
                                });
                                manifest = masterPlaylist = new HlsMasterPlaylist(masterPlaylist.baseUri,
                                        variants, masterPlaylist.subtitles);

                                try {
                                    variantIndices = VideoFormatSelectorUtil.selectVideoFormatsForDefaultDisplay(
                                            context, masterPlaylist.variants, null, false);
                                } catch (MediaCodecUtil.DecoderQueryException e) {
                                    for (Callback callback : callbacks) {
                                        callback.onPlayerError(new ExoPlaybackException(e));
                                    }
                                    return;
                                }
                                if (variantIndices.length == 0) {
                                    for (Callback callback : callbacks) {
                                        callback.onPlayerError(new ExoPlaybackException(
                                                new IllegalStateException("No variants selected.")));
                                    }
                                    return;
                                }
                            }

                            HlsChunkSource chunkSource = new HlsChunkSource(dataSource, uri.toString(),
                                    manifest, bandwidthMeter,
                                    variantIndices, HlsChunkSource.ADAPTIVE_MODE_SPLICE);

                            LoadControl lhc = new DefaultLoadControl(new DefaultAllocator(BUFFER_SEGMENT_SIZE));
                            HlsSampleSource sampleSource = new HlsSampleSource(chunkSource, lhc, BUFFER_SEGMENTS * BUFFER_SEGMENT_SIZE);
                            audioRenderer = new MediaCodecAudioTrackRenderer(sampleSource);
                            videoRenderer = new MediaCodecVideoTrackRenderer(sampleSource,
                                    MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT, 0, handler,
                                    videoRendererEventListener, 50);
                            textRenderer = new Eia608TrackRenderer(sampleSource,
                                    TvInputPlayer.this, handler.getLooper());
                            // TODO: Implement custom HLS source to get the internal track metadata.
                            tvTracks[TvTrackInfo.TYPE_SUBTITLE] = new TvTrackInfo[1];
                            tvTracks[TvTrackInfo.TYPE_SUBTITLE][0] =
                                    new TvTrackInfo.Builder(TvTrackInfo.TYPE_SUBTITLE, "1")
                                            .build();
                            prepareInternal();
                        }

                        @Override
                        public void onSingleManifestError(IOException e) {
                            for (Callback callback : callbacks) {
                                callback.onPlayerError(new ExoPlaybackException(e));
                            }
                        }
                    });
        } else if (sourceType == SOURCE_TYPE_MPEG_DASH) {
            MediaPresentationDescriptionParser parser = new MediaPresentationDescriptionParser();
            final ManifestFetcher<MediaPresentationDescription> manifestFetcher =
                    new ManifestFetcher<>(uri.toString(), dataSource, parser);
            manifestFetcher.singleLoad(handler.getLooper(),
                    new ManifestFetcher.ManifestCallback<MediaPresentationDescription>() {
                        @Override
                        public void onSingleManifest(MediaPresentationDescription manifest) {
                            Period period = manifest.getPeriod(0);
                            LoadControl loadControl = new DefaultLoadControl(new DefaultAllocator(
                                    BUFFER_SEGMENT_SIZE));

                            // Determine which video representations we should use for playback.
                            int maxDecodableFrameSize;
                            try {
                                maxDecodableFrameSize = MediaCodecUtil.maxH264DecodableFrameSize();
                            } catch (MediaCodecUtil.DecoderQueryException e) {
                                for (Callback callback : callbacks) {
                                    callback.onPlayerError(new ExoPlaybackException(e));
                                }
                                return;
                            }

                            int videoAdaptationSetIndex = period.getAdaptationSetIndex(
                                    AdaptationSet.TYPE_VIDEO);
                            List<Representation> videoRepresentations =
                                    period.adaptationSets.get(videoAdaptationSetIndex).representations;
                            ArrayList<Integer> videoRepresentationIndexList = new ArrayList<>();
                            for (int i = 0; i < videoRepresentations.size(); i++) {
                                Format format = videoRepresentations.get(i).format;
                                if (format.width * format.height > maxDecodableFrameSize) {
                                    // Filtering stream that device cannot play
                                } else if (!format.mimeType.equals(MimeTypes.VIDEO_MP4)
                                        && !format.mimeType.equals(MimeTypes.VIDEO_WEBM)) {
                                    // Filtering unsupported mime type
                                } else {
                                    videoRepresentationIndexList.add(i);
                                }
                            }


                            // Build the video renderer.
                            if (videoRepresentationIndexList.isEmpty()) {
                                videoRenderer = new DummyTrackRenderer();
                            } else {
                                DataSource videoDataSource = new DefaultUriDataSource(context, userAgent);
                                DefaultBandwidthMeter videoBandwidthMeter = new DefaultBandwidthMeter();
                                ChunkSource videoChunkSource = new DashChunkSource(manifestFetcher,
                                        DefaultDashTrackSelector.newVideoInstance(context, true, false),
                                        videoDataSource,
                                        new FormatEvaluator.AdaptiveEvaluator(videoBandwidthMeter), LIVE_EDGE_LATENCY_MS,
                                        0, true, null, null);
                                ChunkSampleSource videoSampleSource = new ChunkSampleSource(
                                        videoChunkSource, loadControl,
                                        VIDEO_BUFFER_SEGMENTS * BUFFER_SEGMENT_SIZE);
                                videoRenderer = new MediaCodecVideoTrackRenderer(videoSampleSource,
                                        MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT, 0, handler,
                                        videoRendererEventListener, 50);
                            }

                            // Build the audio chunk sources.
                            int audioAdaptationSetIndex = period.getAdaptationSetIndex(
                                    AdaptationSet.TYPE_AUDIO);
                            AdaptationSet audioAdaptationSet = period.adaptationSets.get(
                                    audioAdaptationSetIndex);
                            List<ChunkSource> audioChunkSourceList = new ArrayList<>();
                            List<TvTrackInfo> audioTrackList = new ArrayList<>();
                            if (audioAdaptationSet != null) {
                                DataSource audioDataSource = new DefaultUriDataSource(context, userAgent);
                                FormatEvaluator audioEvaluator = new FormatEvaluator.FixedEvaluator();
                                List<Representation> audioRepresentations =
                                        audioAdaptationSet.representations;
                                for (int i = 0; i < audioRepresentations.size(); i++) {
                                    Format format = audioRepresentations.get(i).format;
                                    audioTrackList.add(new TvTrackInfo.Builder(TvTrackInfo.TYPE_AUDIO,
                                            Integer.toString(i))
                                            .setAudioChannelCount(format.audioChannels)
                                            .setAudioSampleRate(format.audioSamplingRate)
                                            .setLanguage(format.language)
                                            .build());
                                    audioChunkSourceList.add(new DashChunkSource(manifestFetcher,
                                            DefaultDashTrackSelector.newAudioInstance(),
                                            audioDataSource,
                                            audioEvaluator, LIVE_EDGE_LATENCY_MS, 0, null, null));
                                }
                            }

                            // Build the audio renderer.
                            //final MultiTrackChunkSource audioChunkSource;
                            if (audioChunkSourceList.isEmpty()) {
                                audioRenderer = new DummyTrackRenderer();
                            } else {
                                //audioChunkSource = new MultiTrackChunkSource(audioChunkSourceList);
                                //SampleSource audioSampleSource = new ChunkSampleSource(audioChunkSource,
                                //        loadControl, AUDIO_BUFFER_SEGMENTS * BUFFER_SEGMENT_SIZE);
                                //audioRenderer = new MediaCodecAudioTrackRenderer(audioSampleSource);
                                TvTrackInfo[] tracks = new TvTrackInfo[audioTrackList.size()];
                                audioTrackList.toArray(tracks);
                                tvTracks[TvTrackInfo.TYPE_AUDIO] = tracks;
                                selectedTvTracks[TvTrackInfo.TYPE_AUDIO] = 0;
                                //multiTrackChunkSources[TvTrackInfo.TYPE_AUDIO] = audioChunkSource;
                            }

                            // Build the text renderer.
                            textRenderer = new DummyTrackRenderer();

                            prepareInternal();
                        }

                        @Override
                        public void onSingleManifestError(IOException e) {
                            for (Callback callback : callbacks) {
                                callback.onPlayerError(new ExoPlaybackException(e));
                            }
                        }
                    });
        } else {
            throw new IllegalArgumentException("Unknown source type: " + sourceType);
        }
    }

    public TvTrackInfo[] getTracks(int trackType) {
        if (trackType < 0 || trackType >= tvTracks.length) {
            throw new IllegalArgumentException("Illegal track type: " + trackType);
        }
        return tvTracks[trackType];
    }

    public String getSelectedTrack(int trackType) {
        if (trackType < 0 || trackType >= tvTracks.length) {
            throw new IllegalArgumentException("Illegal track type: " + trackType);
        }
        if (selectedTvTracks[trackType] == NO_TRACK_SELECTED) {
            return null;
        }
        return tvTracks[trackType][selectedTvTracks[trackType]].getId();
    }

    public boolean selectTrack(int trackType, String trackId) {
        if (trackType < 0 || trackType >= tvTracks.length) {
            return false;
        }
        if (trackId == null) {
            player.setRendererEnabled(trackType, false);
        } else {
            int trackIndex = Integer.parseInt(trackId);
            /*
            if (multiTrackChunkSources[trackType] == null) {
                player.setRendererEnabled(trackType, true);
            } else {
                boolean playWhenReady = player.getPlayWhenReady();
                player.setPlayWhenReady(false);
                player.setRendererEnabled(trackType, false);
                player.sendMessage(multiTrackChunkSources[trackType],
                        MultiTrackChunkSource.MSG_SELECT_TRACK, trackIndex);
                player.setRendererEnabled(trackType, true);
                player.setPlayWhenReady(playWhenReady);
            }
            */
        }
        return true;
    }

    public void setPlayWhenReady(boolean playWhenReady) {
        player.setPlayWhenReady(playWhenReady);
    }

    public void setVolume(float volume) {
        this.volume = volume;
        if (player != null && audioRenderer != null) {
            player.sendMessage(audioRenderer, MediaCodecAudioTrackRenderer.MSG_SET_VOLUME,
                    volume);
        }
    }

    public void setSurface(Surface surface) {
        this.surface = surface;
        if (player != null && videoRenderer != null) {
            player.sendMessage(videoRenderer, MediaCodecVideoTrackRenderer.MSG_SET_SURFACE,
                    surface);
        }
    }

    public void seekTo(long position) {
        if (isPlayerPrepared(player)) {  // The player doesn't know the duration until prepared.
            if (player.getDuration() != ExoPlayer.UNKNOWN_TIME) {
                player.seekTo(position);
            }
        } else {
            pendingSeekPosition = position;
        }
    }

    public void stop() {
        player.stop();
    }

    public void release() {
        player.release();
    }

    public void addCallback(Callback callback) {
        callbacks.add(callback);
    }

    public void removeCallback(Callback callback) {
        callbacks.remove(callback);
    }

    private void prepareInternal() {
        player.prepare(audioRenderer, videoRenderer, textRenderer);
        player.sendMessage(audioRenderer, MediaCodecAudioTrackRenderer.MSG_SET_VOLUME,
                volume);
        player.sendMessage(videoRenderer, MediaCodecVideoTrackRenderer.MSG_SET_SURFACE,
                surface);
        // Disable text track by default.
        player.setRendererEnabled(TvTrackInfo.TYPE_SUBTITLE, false);
        for (Callback callback : callbacks) {
            callback.onPrepared();
        }
    }

    private static String getUserAgent(Context context) {
        String versionName;
        try {
            String packageName = context.getPackageName();
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            versionName = info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            versionName = "?";
        }
        return "IptvLiveChannels/" + versionName + " (Linux;Android " + Build.VERSION.RELEASE +
                ") " + "ExoPlayerLib/" + ExoPlayerLibraryInfo.VERSION;
    }

    private static boolean isPlayerPrepared(ExoPlayer player) {
        int state = player.getPlaybackState();
        return state != ExoPlayer.STATE_PREPARING && state != ExoPlayer.STATE_IDLE;
    }

    private static Uri processUriParameters(Uri uri, DefaultHttpDataSource dataSource) {
        String[] parameters = uri.getPath().split("\\|");
        for (int i = 1; i < parameters.length; i++) {
            String[] pair = parameters[i].split("=", 2);
            if (pair.length == 2) {
                dataSource.setRequestProperty(pair[0], pair[1]);
            }
        }

        return uri.buildUpon().path(parameters[0]).build();
    }

    public interface Callback {
        void onPrepared();
        void onPlayerStateChanged(boolean playWhenReady, int state);
        void onPlayWhenReadyCommitted();
        void onPlayerError(ExoPlaybackException e);
        void onDrawnToSurface(Surface surface);
        void onCues(List<Cue> cues);
    }
}
