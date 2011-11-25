package com.max.player.controller;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.media.RemoteControlClient;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;

import com.max.player.MaxPlayerActivity;
import com.max.player.R;
import com.max.player.R.drawable;
import com.max.player.interfaces.MusicFocusable;
import com.max.player.util.AudioFocusHelper;
import com.max.player.util.MediaButtonHelper;
import com.max.player.util.RemoteControlHelper;

public class MusicService extends Service implements OnCompletionListener,
		OnPreparedListener, OnErrorListener, MusicFocusable,
		PrepareMusicRetrieverTask.MusicRetrieverPreparedListener {

	final static String TAG = "MaxPlayer";

	public static final String ACTION_TOGGLE_PLAYBACK = "com.max.player.action.TOGGLE_PLAYBACK";
	public static final String ACTION_PLAY = "com.max.player.action.PLAY";
	public static final String ACTION_PAUSE = "com.max.player.action.PAUSE";
	public static final String ACTION_STOP = "com.max.player.action.STOP";
	public static final String ACTION_SKIP = "com.max.player.action.SKIP";
	public static final String ACTION_REWIND = "com.max.player.action.REWIND";
	public static final String ACTION_URL = "com.max.player.action.URL";

	public static final float DUCK_VOLUME = 0.1f;

	private MediaPlayer mPlayer = null;
	private AudioFocusHelper mAudioFocusHelper = null;

	// states of service:
	private enum State {
		Retrieving, Stopped, Preparing, Playing, Paused
	};

	private State mState = State.Retrieving;
	boolean mStartPlayingAfterRetrieve = false;
	private Uri mWhatToPlayAfterRetrieve = null;

	private enum PauseReason {
		UserRequest, FocusLoss
	};

	private PauseReason mPauseReason = PauseReason.UserRequest;

	enum AudioFocus {
		NoFocusNoDuck, NoFocusCanDuck, Focused
	}

	private AudioFocus mAudioFocus = AudioFocus.NoFocusNoDuck;

	private String mSongTitle = ""; // title of the song we are currently
									// playing
	boolean mIsStreaming = false; // whether the song we are playing is
									// streaming from the network
	private WifiLock mWifiLock; // Wifi lock that we hold when streaming files
								// from the internet

	private final int NOTIFICATION_ID = 1;

	private MusicRetriever mRetriever;
	private RemoteControlClientCompat mRemoteControlClientCompat;
	private Bitmap mDummyAlbumArt; // Dummy album art we will pass to the remote
									// control (if the APIs are available).
	private ComponentName mMediaButtonReceiverComponent;

	private AudioManager mAudioManager;
	private NotificationManager mNotificationManager;

	Notification mNotification = null;

	private void createMediaPlayerIfNeeded() {
		if (mPlayer == null) {
			mPlayer = new MediaPlayer();

			mPlayer.setWakeMode(getApplicationContext(),
					PowerManager.PARTIAL_WAKE_LOCK);
			mPlayer.setOnPreparedListener(this);
			mPlayer.setOnCompletionListener(this);
			mPlayer.setOnErrorListener(this);
		} else
			mPlayer.reset();
	}

	// ----------------------------------------------------------------------------------------------

	@Override
	public void onCreate() {
		Log.i(TAG, "debug: Creating service");

		mWifiLock = ((WifiManager) getSystemService(Context.WIFI_SERVICE))
				.createWifiLock(WifiManager.WIFI_MODE_FULL, "mylock");

		mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		mAudioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

		mRetriever = new MusicRetriever(getContentResolver());
		(new PrepareMusicRetrieverTask(mRetriever, this)).execute();

		if (android.os.Build.VERSION.SDK_INT >= 8)
			mAudioFocusHelper = new AudioFocusHelper(getApplicationContext(),
					this);
		else
			mAudioFocus = AudioFocus.Focused;

		mDummyAlbumArt = BitmapFactory.decodeResource(getResources(),
				R.drawable.dummy_album_art);
		mMediaButtonReceiverComponent = new ComponentName(this,
				MusicIntentReceiver.class);
	}

	// ----------------------------------------------------------------------------------------------

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		String action = intent.getAction();
		if (action.equals(ACTION_TOGGLE_PLAYBACK))
			processTogglePlaybackRequest();
		else if (action.equals(ACTION_PLAY))
			processPlayRequest();
		else if (action.equals(ACTION_PAUSE))
			processPauseRequest();
		else if (action.equals(ACTION_SKIP))
			processSkipRequest();
		else if (action.equals(ACTION_STOP))
			processStopRequest();
		else if (action.equals(ACTION_REWIND))
			processRewindRequest();
		else if (action.equals(ACTION_URL))
			processAddRequest(intent);

		return START_NOT_STICKY;
	}

	// ----------------------------------------------------------------------------------------------

	void processTogglePlaybackRequest() {
		if (mState == State.Paused || mState == State.Stopped) {
			processPlayRequest();
		} else {
			processPauseRequest();
		}
	}

	// ----------------------------------------------------------------------------------------------

	private void processPlayRequest() {
		if (mState == State.Retrieving) {
			mWhatToPlayAfterRetrieve = null; // play a random song
			mStartPlayingAfterRetrieve = true;
			return;
		}

		tryToGetAudioFocus();

		if (mState == State.Stopped) {
			playNextSong(null);
		} else if (mState == State.Paused) {
			mState = State.Playing;
			setUpAsForeground(mSongTitle + " (playing)");
			configAndStartMediaPlayer();
		}

		if (mRemoteControlClientCompat != null) {
			mRemoteControlClientCompat
					.setPlaybackState(RemoteControlClient.PLAYSTATE_PLAYING);
		}
	}

	// ------------------------------------------------------------------------------------------------

	private void processPauseRequest() {
		if (mState == State.Retrieving) {
			mStartPlayingAfterRetrieve = false;
			return;
		}

		if (mState == State.Playing) {
			mState = State.Paused;
			mPlayer.pause();
			relaxResources(false);
		}

		// Tell any remote controls that our playback state is 'paused'.
		if (mRemoteControlClientCompat != null) {
			mRemoteControlClientCompat
					.setPlaybackState(RemoteControlClient.PLAYSTATE_PAUSED);
		}
	}

	// ----------------------------------------------------------------------------------------------
	private void processRewindRequest() {
		if (mState == State.Playing || mState == State.Paused)
			mPlayer.seekTo(0);
	}

	// ----------------------------------------------------------------------------------------------
	private void processSkipRequest() {
		if (mState == State.Playing || mState == State.Paused) {
			tryToGetAudioFocus();
			playNextSong(null);
		}
	}

	// ----------------------------------------------------------------------------------------------
	private void processStopRequest() {
		processStopRequest(false);
	}

	// ----------------------------------------------------------------------------------------------
	private void processStopRequest(boolean force) {
		if (mState == State.Playing || mState == State.Paused || force) {
			mState = State.Stopped;

			relaxResources(true);
			giveUpAudioFocus();

			if (mRemoteControlClientCompat != null) {
				mRemoteControlClientCompat
						.setPlaybackState(RemoteControlClient.PLAYSTATE_STOPPED);
			}
			stopSelf();
		}
	}

	// ----------------------------------------------------------------------------------------------

	private void relaxResources(boolean releaseMediaPlayer) {
		stopForeground(true);

		if (releaseMediaPlayer && mPlayer != null) {
			mPlayer.reset();
			mPlayer.release();
			mPlayer = null;
		}

		if (mWifiLock.isHeld())
			mWifiLock.release();
	}

	// ----------------------------------------------------------------------------------------------

	private void giveUpAudioFocus() {
		if (mAudioFocus == AudioFocus.Focused && mAudioFocusHelper != null
				&& mAudioFocusHelper.abandonFocus())
			mAudioFocus = AudioFocus.NoFocusNoDuck;
	}

	// ----------------------------------------------------------------------------------------------

	private void configAndStartMediaPlayer() {
		if (mAudioFocus == AudioFocus.NoFocusNoDuck) {

			if (mPlayer.isPlaying())
				mPlayer.pause();
			return;
		} else if (mAudioFocus == AudioFocus.NoFocusCanDuck)
			mPlayer.setVolume(DUCK_VOLUME, DUCK_VOLUME); // we'll be relatively
															// quiet
		else
			mPlayer.setVolume(1.0f, 1.0f); // we can be loud

		if (!mPlayer.isPlaying())
			mPlayer.start();
	}

	void processAddRequest(Intent intent) {
		// user wants to play a song directly by URL or path. The URL or path
		// comes in the "data"
		// part of the Intent. This Intent is sent by {@link MainActivity} after
		// the user
		// specifies the URL/path via an alert box.
		if (mState == State.Retrieving) {
			// we'll play the requested URL right after we finish retrieving
			mWhatToPlayAfterRetrieve = intent.getData();
			mStartPlayingAfterRetrieve = true;
		} else if (mState == State.Playing || mState == State.Paused
				|| mState == State.Stopped) {
			Log.i(TAG, "Playing from URL/path: " + intent.getData().toString());
			tryToGetAudioFocus();
			playNextSong(intent.getData().toString());
		}
	}

	void tryToGetAudioFocus() {
		if (mAudioFocus != AudioFocus.Focused && mAudioFocusHelper != null
				&& mAudioFocusHelper.requestFocus())
			mAudioFocus = AudioFocus.Focused;
	}

	/**
	 * Starts playing the next song. If manualUrl is null, the next song will be
	 * randomly selected from our Media Retriever (that is, it will be a random
	 * song in the user's device). If manualUrl is non-null, then it specifies
	 * the URL or path to the song that will be played next.
	 */
	void playNextSong(String manualUrl) {
		mState = State.Stopped;
		relaxResources(false); // release everything except MediaPlayer

		try {
			MusicRetriever.Item playingItem = null;
			if (manualUrl != null) {
				// set the source of the media player to a manual URL or path
				createMediaPlayerIfNeeded();
				mPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
				mPlayer.setDataSource(manualUrl);
				mIsStreaming = manualUrl.startsWith("http:")
						|| manualUrl.startsWith("https:");

				playingItem = new MusicRetriever.Item(0, null, manualUrl, null,
						0);
			} else {
				mIsStreaming = false; // playing a locally available song

				playingItem = mRetriever.getRandomItem();
				if (playingItem == null) {
					Toast.makeText(
							this,
							"No available music to play. Place some music on your external storage "
									+ "device (e.g. your SD card) and try again.",
							Toast.LENGTH_LONG).show();
					processStopRequest(true); // stop everything!
					return;
				}

				// set the source of the media player a a content URI
				createMediaPlayerIfNeeded();
				mPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
				mPlayer.setDataSource(getApplicationContext(),
						playingItem.getURI());
			}

			mSongTitle = playingItem.getTitle();

			mState = State.Preparing;
			setUpAsForeground(mSongTitle + " (loading)");

			// Use the media button APIs (if available) to register ourselves
			// for media button
			// events

			MediaButtonHelper.registerMediaButtonEventReceiverCompat(
					mAudioManager, mMediaButtonReceiverComponent);

			// Use the remote control APIs (if available) to set the playback
			// state

			if (mRemoteControlClientCompat == null) {
				Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
				intent.setComponent(mMediaButtonReceiverComponent);
				mRemoteControlClientCompat = new RemoteControlClientCompat(
						PendingIntent.getBroadcast(this /* context */, 0 /*
																		 * requestCode
																		 * ,
																		 * ignored
																		 */,
								intent /* intent */, 0 /* flags */));
				RemoteControlHelper.registerRemoteControlClient(mAudioManager,
						mRemoteControlClientCompat);
			}

			mRemoteControlClientCompat
					.setPlaybackState(RemoteControlClient.PLAYSTATE_PLAYING);

			mRemoteControlClientCompat
					.setTransportControlFlags(RemoteControlClient.FLAG_KEY_MEDIA_PLAY
							| RemoteControlClient.FLAG_KEY_MEDIA_PAUSE
							| RemoteControlClient.FLAG_KEY_MEDIA_NEXT
							| RemoteControlClient.FLAG_KEY_MEDIA_STOP);

			// Update the remote controls
			mRemoteControlClientCompat
					.editMetadata(true)
					.putString(MediaMetadataRetriever.METADATA_KEY_ARTIST,
							playingItem.getArtist())
					.putString(MediaMetadataRetriever.METADATA_KEY_ALBUM,
							playingItem.getAlbum())
					.putString(MediaMetadataRetriever.METADATA_KEY_TITLE,
							playingItem.getTitle())
					.putLong(MediaMetadataRetriever.METADATA_KEY_DURATION,
							playingItem.getDuration())
					// TODO: fetch real item artwork
					.putBitmap(
							RemoteControlClientCompat.MetadataEditorCompat.METADATA_KEY_ARTWORK,
							mDummyAlbumArt).apply();

			// starts preparing the media player in the background. When it's
			// done, it will call
			// our OnPreparedListener (that is, the onPrepared() method on this
			// class, since we set
			// the listener to 'this').
			//
			// Until the media player is prepared, we *cannot* call start() on
			// it!
			mPlayer.prepareAsync();

			// If we are streaming from the internet, we want to hold a Wifi
			// lock, which prevents
			// the Wifi radio from going to sleep while the song is playing. If,
			// on the other hand,
			// we are *not* streaming, we want to release the lock if we were
			// holding it before.
			if (mIsStreaming)
				mWifiLock.acquire();
			else if (mWifiLock.isHeld())
				mWifiLock.release();
		} catch (IOException ex) {
			Log.e("MusicService",
					"IOException playing next song: " + ex.getMessage());
			ex.printStackTrace();
		}
	}

	/** Called when media player is done playing current song. */
	public void onCompletion(MediaPlayer player) {
		// The media player finished playing the current song, so we go ahead
		// and start the next.
		playNextSong(null);
	}

	/** Called when media player is done preparing. */
	public void onPrepared(MediaPlayer player) {
		// The media player is done preparing. That means we can start playing!
		mState = State.Playing;
		updateNotification(mSongTitle + " (playing)");
		configAndStartMediaPlayer();
	}

	/** Updates the notification. */
	void updateNotification(String text) {
		PendingIntent pi = PendingIntent.getActivity(getApplicationContext(),
				0,
				new Intent(getApplicationContext(), MaxPlayerActivity.class),
				PendingIntent.FLAG_UPDATE_CURRENT);
		mNotification.setLatestEventInfo(getApplicationContext(),
				"RandomMusicPlayer", text, pi);
		mNotificationManager.notify(NOTIFICATION_ID, mNotification);
	}

	/**
	 * Configures service as a foreground service. A foreground service is a
	 * service that's doing something the user is actively aware of (such as
	 * playing music), and must appear to the user as a notification. That's why
	 * we create the notification here.
	 */
	void setUpAsForeground(String text) {
		PendingIntent pi = PendingIntent.getActivity(getApplicationContext(),
				0,
				new Intent(getApplicationContext(), MaxPlayerActivity.class),
				PendingIntent.FLAG_UPDATE_CURRENT);
		mNotification = new Notification();
		mNotification.tickerText = text;
		mNotification.icon = R.drawable.ic_stat_playing;
		mNotification.flags |= Notification.FLAG_ONGOING_EVENT;
		mNotification.setLatestEventInfo(getApplicationContext(),
				"RandomMusicPlayer", text, pi);
		startForeground(NOTIFICATION_ID, mNotification);
	}

	/**
	 * Called when there's an error playing media. When this happens, the media
	 * player goes to the Error state. We warn the user about the error and
	 * reset the media player.
	 */
	public boolean onError(MediaPlayer mp, int what, int extra) {
		Toast.makeText(getApplicationContext(),
				"Media player error! Resetting.", Toast.LENGTH_SHORT).show();
		Log.e(TAG,
				"Error: what=" + String.valueOf(what) + ", extra="
						+ String.valueOf(extra));

		mState = State.Stopped;
		relaxResources(true);
		giveUpAudioFocus();
		return true; // true indicates we handled the error
	}

	public void onGainedAudioFocus() {
		Toast.makeText(getApplicationContext(), "gained audio focus.",
				Toast.LENGTH_SHORT).show();
		mAudioFocus = AudioFocus.Focused;

		// restart media player with new focus settings
		if (mState == State.Playing)
			configAndStartMediaPlayer();
	}

	public void onLostAudioFocus(boolean canDuck) {
		Toast.makeText(getApplicationContext(),
				"lost audio focus." + (canDuck ? "can duck" : "no duck"),
				Toast.LENGTH_SHORT).show();
		mAudioFocus = canDuck ? AudioFocus.NoFocusCanDuck
				: AudioFocus.NoFocusNoDuck;

		// start/restart/pause media player with new focus settings
		if (mPlayer != null && mPlayer.isPlaying())
			configAndStartMediaPlayer();
	}

	public void onMusicRetrieverPrepared() {
		// Done retrieving!
		mState = State.Stopped;

		// If the flag indicates we should start playing after retrieving, let's
		// do that now.
		if (mStartPlayingAfterRetrieve) {
			tryToGetAudioFocus();
			playNextSong(mWhatToPlayAfterRetrieve == null ? null
					: mWhatToPlayAfterRetrieve.toString());
		}
	}

	@Override
	public void onDestroy() {
		// Service is being killed, so make sure we release our resources
		mState = State.Stopped;
		relaxResources(true);
		giveUpAudioFocus();
	}

	@Override
	public IBinder onBind(Intent arg0) {
		return null;
	}
}
