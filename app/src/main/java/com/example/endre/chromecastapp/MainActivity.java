package com.example.endre.chromecastapp;

import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.support.v7.app.MediaRouteActionProvider;
import android.support.v7.media.MediaRouteSelector;
import android.support.v7.media.MediaRouter;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;

import com.google.android.gms.cast.ApplicationMetadata;
import com.google.android.gms.cast.Cast;
import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.RemoteMediaPlayer;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;

import java.io.IOException;


public class MainActivity extends ActionBarActivity {



    private Button mButton;

    private MediaRouter mMediaRouter;
    private MediaRouteSelector mMediaRouteSelector;
    private MediaRouter.Callback mMediaRouterCallback;
    private CastDevice mSelectedDevice;
    private GoogleApiClient mApiClient;
    private RemoteMediaPlayer mRemoteMediaPlayer;
    private Cast.Listener mCastClientListener;
    private boolean mWaitingForReconnect = false;
    private boolean mApplicationStarted = false;
    private boolean mVideoIsLoaded;
    private boolean mIsPlaying;


    @Override
    protected void onCreate( Bundle savedInstanceState ) {
        super.onCreate( savedInstanceState );
        setContentView( R.layout.activity_main );

        mButton = (Button) findViewById( R.id.button );
        mButton.setOnClickListener( new View.OnClickListener() {
            @Override
            public void onClick( View v ) {
                if( !mVideoIsLoaded )
                    startVideo();
                else
                    controlVideo();
            }
        });

        initMediaRouter();
    }

    private void initMediaRouter() {
        // Configure Cast device discovery
        mMediaRouter = MediaRouter.getInstance( getApplicationContext() );
        mMediaRouteSelector = new MediaRouteSelector.Builder()
                .addControlCategory(
                        CastMediaControlIntent.categoryForCast(getString(R.string.app_id)) )
                .build();
        mMediaRouterCallback = new MediaRouterCallback();
    }

    private void initCastClientListener() {
        mCastClientListener = new Cast.Listener() {
            @Override
            public void onApplicationStatusChanged() {
            }
            @Override
            public void onVolumeChanged() {
            }
            @Override
            public void onApplicationDisconnected( int statusCode ) {
                teardown();
            }
        };
        System.out.println("init cast listener");
    }

    private void initRemoteMediaPlayer() {
        mRemoteMediaPlayer = new RemoteMediaPlayer();
        mRemoteMediaPlayer.setOnStatusUpdatedListener( new RemoteMediaPlayer.OnStatusUpdatedListener() {
            @Override
            public void onStatusUpdated() {
                MediaStatus mediaStatus = mRemoteMediaPlayer.getMediaStatus();
                mIsPlaying = mediaStatus.getPlayerState() == MediaStatus.PLAYER_STATE_PLAYING;
            }
        });
        mRemoteMediaPlayer.setOnMetadataUpdatedListener(new RemoteMediaPlayer.OnMetadataUpdatedListener() {
            @Override
            public void onMetadataUpdated() {
            }
        });
    }


    @Override
    public boolean onCreateOptionsMenu( Menu menu ) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.menu_main, menu);
        MenuItem mediaRouteMenuItem = menu.findItem( R.id.media_route_menu_item );
        MediaRouteActionProvider mediaRouteActionProvider = (MediaRouteActionProvider) MenuItemCompat.getActionProvider( mediaRouteMenuItem );
        mediaRouteActionProvider.setRouteSelector( mMediaRouteSelector );
        return true;
    }



    @Override
    protected void onResume() {
        super.onResume();
        // Start media router discovery
        mMediaRouter.addCallback(mMediaRouteSelector, mMediaRouterCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY);
    }

    @Override
    protected void onPause() {
        if ( isFinishing() ) {
            // End media router discovery
            mMediaRouter.removeCallback( mMediaRouterCallback );
        }
        super.onPause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mMediaRouter.addCallback(mMediaRouteSelector, mMediaRouterCallback,
                MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY);
    }

    @Override
    protected void onStop() {
        mMediaRouter.removeCallback(mMediaRouterCallback);
        super.onStop();
    }

    @Override
    public void onDestroy() {
        teardown();
        super.onDestroy();
    }

    private void startVideo() {
        System.out.println("start video");
        MediaMetadata mediaMetadata = new MediaMetadata( MediaMetadata.MEDIA_TYPE_MOVIE );
        mediaMetadata.putString( MediaMetadata.KEY_TITLE, getString( R.string.video_title ) );

        MediaInfo mediaInfo = new MediaInfo.Builder( getString( R.string.video_url ) )
                .setContentType( getString( R.string.content_type_mp4 ) )
                .setStreamType( MediaInfo.STREAM_TYPE_BUFFERED )
                .setMetadata( mediaMetadata )
                .build();
        try {
            mRemoteMediaPlayer.load( mApiClient, mediaInfo, true )
                    .setResultCallback( new ResultCallback<RemoteMediaPlayer.MediaChannelResult>() {
                        @Override
                        public void onResult( RemoteMediaPlayer.MediaChannelResult mediaChannelResult ) {
                            if( mediaChannelResult.getStatus().isSuccess() ) {
                                mVideoIsLoaded = true;
                                mButton.setText( getString( R.string.pause_video ) );
                            }
                        }
                    } );
        } catch( Exception e ) {
        }
    }

    private void controlVideo() {
        if( mRemoteMediaPlayer == null || !mVideoIsLoaded )
            return;
        if( mIsPlaying ) {
            mRemoteMediaPlayer.pause( mApiClient );
            mButton.setText( getString( R.string.resume_video ) );
        } else {
            mRemoteMediaPlayer.play( mApiClient );
            mButton.setText( getString( R.string.pause_video ) );
        }
    }

    private void launchReceiver() {
        Cast.CastOptions.Builder apiOptionsBuilder = Cast.CastOptions
                .builder( mSelectedDevice, mCastClientListener );
        GoogleApiClient.ConnectionCallbacks mConnectionCallbacks = new GoogleApiClient.ConnectionCallbacks() {

            @Override
            public void onConnected( Bundle hint ) {
                if( mWaitingForReconnect ) {
                    mWaitingForReconnect = false;
                    reconnectChannels( hint );
                } else {
                    try {
                        Cast.CastApi.launchApplication( mApiClient, getString( R.string.app_id ), false )
                                .setResultCallback( new ResultCallback<Cast.ApplicationConnectionResult>() {
                                                        @Override
                                                        public void onResult(Cast.ApplicationConnectionResult applicationConnectionResult) {
                                                            Status status = applicationConnectionResult.getStatus();
                                                            if( status.isSuccess() ) {
                                                                //Values that can be useful for storing/logic
                                                                ApplicationMetadata applicationMetadata = applicationConnectionResult.getApplicationMetadata();
                                                                String sessionId = applicationConnectionResult.getSessionId();
                                                                String applicationStatus = applicationConnectionResult.getApplicationStatus();
                                                                boolean wasLaunched = applicationConnectionResult.getWasLaunched();
                                                                mApplicationStarted = true;
                                                                reconnectChannels( null );
                                                            }
                                                        }
                                                    }
                                );
                    } catch ( Exception e ) {

                    }
                }
            }

            @Override
            public void onConnectionSuspended(int i) {
                mWaitingForReconnect = true;
            }
        };
        ConnectionFailedListener mConnectionFailedListener = new ConnectionFailedListener();
        mApiClient = new GoogleApiClient.Builder( this )
                .addApi( Cast.API, apiOptionsBuilder.build() )
                .addConnectionCallbacks( mConnectionCallbacks )
                .addOnConnectionFailedListener( mConnectionFailedListener )
                .build();
        mApiClient.connect();
    }

    private void reconnectChannels( Bundle hint ) {
        if( ( hint != null ) && hint.getBoolean( Cast.EXTRA_APP_NO_LONGER_RUNNING ) ) {
            //Log.e( TAG, "App is no longer running" );
            teardown();
        } else {
            try {
                Cast.CastApi.setMessageReceivedCallbacks( mApiClient, mRemoteMediaPlayer.getNamespace(), mRemoteMediaPlayer );
            } catch( IOException e ) {

            } catch( NullPointerException e ) {

            }
        }
    }

    private void teardown() {
        if( mApiClient != null ) {
            if( mApplicationStarted ) {
                try {
                    Cast.CastApi.stopApplication( mApiClient );
                    if( mRemoteMediaPlayer != null ) {
                        Cast.CastApi.removeMessageReceivedCallbacks( mApiClient, mRemoteMediaPlayer.getNamespace() );
                        mRemoteMediaPlayer = null;
                    }
                } catch( IOException e ) {
                    //Log.e( TAG, "Exception while removing application " + e );
                }
                mApplicationStarted = false;
            }
            if( mApiClient.isConnected() )
                mApiClient.disconnect();
            mApiClient = null;
        }
        mSelectedDevice = null;
        mVideoIsLoaded = false;
    }

    private class ConnectionFailedListener implements GoogleApiClient.OnConnectionFailedListener {
        @Override
        public void onConnectionFailed( ConnectionResult connectionResult ) {
            teardown();
        }
    }

    private class ConnectionCallbacks implements GoogleApiClient.ConnectionCallbacks {
        @Override
        public void onConnected( Bundle hint ) {
            if( mWaitingForReconnect ) {
                mWaitingForReconnect = false;
                reconnectChannels( hint );
            } else {
                try {
                    Cast.CastApi.launchApplication( mApiClient, getString( R.string.app_id ), false )
                            .setResultCallback( new ResultCallback<Cast.ApplicationConnectionResult>() {
                                                    @Override
                                                    public void onResult(Cast.ApplicationConnectionResult applicationConnectionResult) {
                                                        Status status = applicationConnectionResult.getStatus();
                                                        if( status.isSuccess() ) {
                                                            //Values that can be useful for storing/logic
                                                            ApplicationMetadata applicationMetadata = applicationConnectionResult.getApplicationMetadata();
                                                            String sessionId = applicationConnectionResult.getSessionId();
                                                            String applicationStatus = applicationConnectionResult.getApplicationStatus();
                                                            boolean wasLaunched = applicationConnectionResult.getWasLaunched();
                                                            mApplicationStarted = true;
                                                            reconnectChannels( null );
                                                        }
                                                    }
                                                }
                            );
                } catch ( Exception e ) {

                }
            }
        }
        @Override
        public void onConnectionSuspended(int i) {
            mWaitingForReconnect = true;
        }
    }

    private class MediaRouterCallback extends MediaRouter.Callback {
        @Override
        public void onRouteSelected(MediaRouter router, MediaRouter.RouteInfo info) {
            initCastClientListener();
            initRemoteMediaPlayer();
            mSelectedDevice = CastDevice.getFromBundle( info.getExtras() );
            launchReceiver();

        }
        @Override
        public void onRouteUnselected( MediaRouter router, MediaRouter.RouteInfo info ) {
            teardown();
            mSelectedDevice = null;
            mButton.setText( getString( R.string.play_video ) );
            mVideoIsLoaded = false;
        }
    }


}
