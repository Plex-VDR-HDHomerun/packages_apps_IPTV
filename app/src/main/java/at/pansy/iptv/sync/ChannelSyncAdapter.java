package at.pansy.iptv.sync;

import android.accounts.Account;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentUris;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.SyncResult;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;
import android.util.LongSparseArray;

import java.util.ArrayList;
import java.util.List;

import at.pansy.iptv.R;
import at.pansy.iptv.domain.Program;
import at.pansy.iptv.util.IptvUtil;
import at.pansy.iptv.util.TvContractUtil;
import at.pansy.iptv.xmltv.XmlTvParser;

public class ChannelSyncAdapter {
    public static final String TAG = "ChannelSyncAdapter";

    public static final String BUNDLE_KEY_INPUT_ID = "bundle_key_input_id";
    public static final String BUNDLE_KEY_CURRENT_PROGRAM_ONLY = "bundle_key_current_program_only";
    public static final long FULL_SYNC_FREQUENCY_SEC = 60 * 60 * 24;  // daily

    private static final int FULL_SYNC_WINDOW_SEC = 60 * 60 * 24 * 14;  // 2 weeks
    private static final int SHORT_SYNC_WINDOW_SEC = 60 * 60;  // 1 hour
    private static final int BATCH_OPERATION_COUNT = 100;

    private final Context context;

    public ChannelSyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
        this.context = context;
    }

    public ChannelSyncAdapter(Context context, boolean autoInitialize, boolean allowParallelSyncs) {
        super(context, autoInitialize, allowParallelSyncs);
        this.context = context;
    }

    /**
     * Called periodically by the system in every {@code FULL_SYNC_FREQUENCY_SEC}.
     */
    @Override
    public void onPerformSync(Account account, Bundle extras, String authority,
                              ContentProviderClient provider, SyncResult syncResult) {

        Log.d(TAG, "onPerformSync(" + account + ", " + authority + ", " + extras + ")");
        String inputId = extras.getString(ChannelSyncAdapter.BUNDLE_KEY_INPUT_ID);
        if (inputId == null) {
            return;
        }

        XmlTvParser.TvListing listings = IptvUtil.getTvListings(context,
                context.getString(R.string.setup_root_server_m3u), IptvUtil.FORMAT_XMLTV);

        XmlTvParser.TvListing channelListings = IptvUtil.getTvListings(context,
                context.getString(R.string.setup_root_server_xmltv), IptvUtil.FORMAT_M3U);
        listings.setChannels(channelListings.channels);


        LongSparseArray<XmlTvParser.XmlTvChannel> channelMap = TvContractUtil.buildChannelMap(
                context.getContentResolver(), inputId, listings.channels);
        boolean currentProgramOnly = extras.getBoolean(
                ChannelSyncAdapter.BUNDLE_KEY_CURRENT_PROGRAM_ONLY, false);
        long startMs = System.currentTimeMillis();
        long endMs = startMs + FULL_SYNC_WINDOW_SEC * 1000;
        if (currentProgramOnly) {
            // This is requested from the setup activity, in this case, users don't need to wait for
            // the full sync. Sync the current programs first and do the full sync later in the
            // background.
            endMs = startMs + SHORT_SYNC_WINDOW_SEC * 1000;
        }
        for (int i = 0; i < channelMap.size(); ++i) {
            Uri channelUri = TvContract.buildChannelUri(channelMap.keyAt(i));
            List<Program> programs = getPrograms(channelUri, channelMap.valueAt(i),
                    listings.programs, startMs, endMs);
            updatePrograms(channelUri, programs);
        }
    }

    /**
     * Returns a list of programs for the given time range.
     *
     * @param channelUri The channel where the program info will be added.
     * @param channel The {@link XmlTvParser.XmlTvChannel} for the programs to return.
     * @param programs The feed fetched from cloud.
     * @param startTimeMs The start time of the range requested.
     * @param endTimeMs The end time of the range requested.
     */
    private List<Program> getPrograms(Uri channelUri, XmlTvParser.XmlTvChannel channel,
                                      List<XmlTvParser.XmlTvProgram> programs, long startTimeMs, long endTimeMs) {
        if (startTimeMs > endTimeMs) {
            throw new IllegalArgumentException();
        }
        List<XmlTvParser.XmlTvProgram> channelPrograms = new ArrayList<>();
        for (XmlTvParser.XmlTvProgram program : programs) {
            if (program.channelId.equals(channel.id)) {
                channelPrograms.add(program);
            }
        }

        List<Program> programForGivenTime = new ArrayList<>();
        if (!channel.repeatPrograms) {
            for (XmlTvParser.XmlTvProgram program : channelPrograms) {
                if (program.startTimeUtcMillis <= endTimeMs
                        && program.endTimeUtcMillis >= startTimeMs) {
                    programForGivenTime.add(new Program.Builder()
                            .setChannelId(ContentUris.parseId(channelUri))
                            .setTitle(program.title)
                            .setDescription(program.description)
                            .setContentRatings(XmlTvParser.xmlTvRatingToTvContentRating(
                                    program.rating))
                            .setCanonicalGenres(program.category)
                            .setPosterArtUri(program.icon != null ? program.icon.src : null)
                            .setInternalProviderData(TvContractUtil.
                                    convertVideoInfoToInternalProviderData(
                                            program.videoType,
                                            program.videoSrc != null ? program.videoSrc : channel.url))
                            .setStartTimeUtcMillis(program.startTimeUtcMillis)
                            .setEndTimeUtcMillis(program.endTimeUtcMillis)
                            .build()
                    );
                }
            }
            return programForGivenTime;
        }

        // If repeat-programs is on, schedule the programs sequentially in a loop. To make every
        // device play the same program in a given channel and time, we assumes the loop started
        // from the epoch time.
        long totalDurationMs = 0;
        for (XmlTvParser.XmlTvProgram program : channelPrograms) {
            totalDurationMs += program.getDurationMillis();
        }

        long programStartTimeMs = startTimeMs - startTimeMs % totalDurationMs;
        int i = 0;
        final int programCount = channelPrograms.size();
        while (programStartTimeMs < endTimeMs) {
            XmlTvParser.XmlTvProgram programInfo = channelPrograms.get(i++ % programCount);
            long programEndTimeMs = programStartTimeMs + programInfo.getDurationMillis();
            if (programEndTimeMs < startTimeMs) {
                programStartTimeMs = programEndTimeMs;
                continue;
            }
            programForGivenTime.add(new Program.Builder()
                    .setChannelId(ContentUris.parseId(channelUri))
                    .setTitle(programInfo.title)
                    .setDescription(programInfo.description)
                    .setContentRatings(XmlTvParser.xmlTvRatingToTvContentRating(
                            programInfo.rating))
                    .setCanonicalGenres(programInfo.category)
                    .setPosterArtUri(programInfo.icon.src)
                    // NOTE: {@code COLUMN_INTERNAL_PROVIDER_DATA} is a private field where
                    // TvInputService can store anything it wants. Here, we store video type and
                    // video URL so that TvInputService can play the video later with this field.
                    .setInternalProviderData(TvContractUtil.convertVideoInfoToInternalProviderData(
                            programInfo.videoType,
                            programInfo.videoSrc != null ? programInfo.videoSrc : channel.url))
                    .setStartTimeUtcMillis(programStartTimeMs)
                    .setEndTimeUtcMillis(programEndTimeMs)
                    .build()
            );
            programStartTimeMs = programEndTimeMs;
        }
        return programForGivenTime;
    }

    /**
     * Updates the system database, TvProvider, with the given programs.
     *
     * <p>If there is any overlap between the given and existing programs, the existing ones
     * will be updated with the given ones if they have the same title or replaced.
     *
     * @param channelUri The channel where the program info will be added.
     * @param newPrograms A list of {@link Program} instances which includes program
     *         information.
     */
    private void updatePrograms(Uri channelUri, List<Program> newPrograms) {
        final int fetchedProgramsCount = newPrograms.size();
        if (fetchedProgramsCount == 0) {
            return;
        }
        List<Program> oldPrograms = TvContractUtil.getPrograms(context.getContentResolver(),
                channelUri);
        Program firstNewProgram = newPrograms.get(0);
        int oldProgramsIndex = 0;
        int newProgramsIndex = 0;
        // Skip the past programs. They will be automatically removed by the system.
        for (Program program : oldPrograms) {
            oldProgramsIndex++;
            if(program.getEndTimeUtcMillis() > firstNewProgram.getStartTimeUtcMillis()) {
                break;
            }
        }
        // Compare the new programs with old programs one by one and update/delete the old one or
        // insert new program if there is no matching program in the database.
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        while (newProgramsIndex < fetchedProgramsCount) {
            Program oldProgram = oldProgramsIndex < oldPrograms.size()
                    ? oldPrograms.get(oldProgramsIndex) : null;
            Program newProgram = newPrograms.get(newProgramsIndex);
            boolean addNewProgram = false;
            if (oldProgram != null) {
                if (oldProgram.equals(newProgram)) {
                    // Exact match. No need to update. Move on to the next programs.
                    oldProgramsIndex++;
                    newProgramsIndex++;
                } else if (needsUpdate(oldProgram, newProgram)) {
                    // Partial match. Update the old program with the new one.
                    // NOTE: Use 'update' in this case instead of 'insert' and 'delete'. There could
                    // be application specific settings which belong to the old program.
                    ops.add(ContentProviderOperation.newUpdate(
                            TvContract.buildProgramUri(oldProgram.getProgramId()))
                            .withValues(newProgram.toContentValues())
                            .build());
                    oldProgramsIndex++;
                    newProgramsIndex++;
                } else if (oldProgram.getEndTimeUtcMillis() < newProgram.getEndTimeUtcMillis()) {
                    // No match. Remove the old program first to see if the next program in
                    // {@code oldPrograms} partially matches the new program.
                    ops.add(ContentProviderOperation.newDelete(
                            TvContract.buildProgramUri(oldProgram.getProgramId()))
                            .build());
                    oldProgramsIndex++;
                } else {
                    // No match. The new program does not match any of the old programs. Insert it
                    // as a new program.
                    addNewProgram = true;
                    newProgramsIndex++;
                }
            } else {
                // No old programs. Just insert new programs.
                addNewProgram = true;
                newProgramsIndex++;
            }
            if (addNewProgram) {
                ops.add(ContentProviderOperation
                        .newInsert(TvContract.Programs.CONTENT_URI)
                        .withValues(newProgram.toContentValues())
                        .build());
            }
            // Throttle the batch operation not to cause TransactionTooLargeException.
            if (ops.size() > BATCH_OPERATION_COUNT
                    || newProgramsIndex >= fetchedProgramsCount) {
                try {
                    context.getContentResolver().applyBatch(TvContract.AUTHORITY, ops);
                } catch (RemoteException | OperationApplicationException e) {
                    Log.e(TAG, "Failed to insert programs.", e);
                    return;
                }
                ops.clear();
            }
        }
    }

    /**
     * Returns {@code true} if the {@code oldProgram} program needs to be updated with the
     * {@code newProgram} program.
     */
    private boolean needsUpdate(Program oldProgram, Program newProgram) {
        // NOTE: Here, we update the old program if it has the same title and overlaps with the new
        // program. The test logic is just an example and you can modify this. E.g. check whether
        // the both programs have the same program ID if your EPG supports any ID for the programs.
        return oldProgram.getTitle().equals(newProgram.getTitle())
                && oldProgram.getStartTimeUtcMillis() <= newProgram.getEndTimeUtcMillis()
                && newProgram.getStartTimeUtcMillis() <= oldProgram.getEndTimeUtcMillis();
    }
}
