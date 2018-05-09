package at.pansy.iptv.setup;

import android.app.Activity;
import android.content.Intent;
import android.media.tv.TvInputInfo;
import android.os.Bundle;
import android.support.v17.leanback.app.GuidedStepFragment;
import android.util.Log;
import android.widget.Toast;

import at.pansy.iptv.dataservice.DataServiceClient;
import at.pansy.iptv.R;
import at.pansy.iptv.dataservice.DataService;
import at.pansy.iptv.sync.ChannelSyncAdapter;
import at.pansy.iptv.client.Connection;

public class SetupActivity extends Activity {
    private static final String TAG = "Setup";

    private String mInputId;
    private Connection mConnection;

    private ChannelSyncAdapter channelSync;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mInputId = getIntent().getStringExtra(TvInputInfo.EXTRA_INPUT_ID);

        Log.i(TAG, "creating IPTV connection ...");
        mConnection = new Connection("AndroidTV Settings");

        GuidedStepFragment.addAsRoot(this, new SetupFragment(), android.R.id.content);
    }

    String getInputId() {
        return mInputId;
    }

    public void registerChannels(final ChannelSyncAdapter.ProgressCallback progress) {
        // sync channels
        channelSync = new ChannelSyncAdapter(mConnection, this, mInputId);
        SetupUtils.setInputId(this, mInputId);

        String server = SetupUtils.getServer(SetupActivity.this);

        if(!mConnection.open(server)) {
            Toast.makeText(this, getString(R.string.connect_unable), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                channelSync.setProgressCallback(progress);
                channelSync.syncChannels();
                mConnection.close();

                new DataServiceClient(SetupActivity.this, new DataService.Listener() {
                    @Override
                    public void onConnected(DataService service) {
                        service.scheduleSyncJob(true);
                    }

                    @Override
                    public void onConnectionError(DataService service) {
                    }

                    @Override
                    public void onMovieUpdate(DataService service) {
                    }

                    @Override
                    public void onTimersUpdated(DataService service) {
                    }
                });

                // connect data service
                Intent serviceIntent = new Intent(SetupActivity.this, DataService.class);
                startService(serviceIntent);
            }
        }).start();

    }
}
