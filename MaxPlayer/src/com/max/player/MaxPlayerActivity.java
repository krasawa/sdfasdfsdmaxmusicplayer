package com.max.player;

import com.max.player.controller.MusicService;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;

public class MaxPlayerActivity extends Activity implements OnClickListener{
	 final String SUGGESTED_URL = "http://www.vorbis.com/music/Epoq-Lepidoptera.ogg";

	    Button mPlayButton;
	    Button mPauseButton;
	    Button mSkipButton;
	    Button mRewindButton;
	    Button mStopButton;
	    Button mEjectButton;

	    @Override
	    public void onCreate(Bundle savedInstanceState) {
	        super.onCreate(savedInstanceState);
	        setContentView(R.layout.main);

	        mPlayButton = (Button) findViewById(R.id.playbutton);
	        mPauseButton = (Button) findViewById(R.id.pausebutton);
	        mSkipButton = (Button) findViewById(R.id.skipbutton);
	        mRewindButton = (Button) findViewById(R.id.rewindbutton);
	        mStopButton = (Button) findViewById(R.id.stopbutton);
	        mEjectButton = (Button) findViewById(R.id.ejectbutton);

	        mPlayButton.setOnClickListener(this);
	        mPauseButton.setOnClickListener(this);
	        mSkipButton.setOnClickListener(this);
	        mRewindButton.setOnClickListener(this);
	        mStopButton.setOnClickListener(this);
	        mEjectButton.setOnClickListener(this);
	    }

	    public void onClick(View target) {
	        // Send the correct intent to the MusicService, according to the button that was clicked
	        if (target == mPlayButton)
	            startService(new Intent(MusicService.ACTION_PLAY));
	        else if (target == mPauseButton)
	            startService(new Intent(MusicService.ACTION_PAUSE));
	        else if (target == mSkipButton)
	            startService(new Intent(MusicService.ACTION_SKIP));
	        else if (target == mRewindButton)
	            startService(new Intent(MusicService.ACTION_REWIND));
	        else if (target == mStopButton)
	            startService(new Intent(MusicService.ACTION_STOP));
	        else if (target == mEjectButton) {
	            showUrlDialog();
	        }
	    }

	    void showUrlDialog() {
	        AlertDialog.Builder alertBuilder = new AlertDialog.Builder(this);
	        alertBuilder.setTitle("Manual Input");
	        alertBuilder.setMessage("Enter a URL (must be http://)");
	        final EditText input = new EditText(this);
	        alertBuilder.setView(input);

	        input.setText(SUGGESTED_URL);

	        alertBuilder.setPositiveButton("Play!", new DialogInterface.OnClickListener() {
	            public void onClick(DialogInterface dlg, int whichButton) {
	                // Send an intent with the URL of the song to play. This is expected by
	                // MusicService.
	                Intent i = new Intent(MusicService.ACTION_URL);
	                Uri uri = Uri.parse(input.getText().toString());
	                i.setData(uri);
	                startService(i);
	            }
	        });
	        alertBuilder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
	            public void onClick(DialogInterface dlg, int whichButton) {}
	        });

	        alertBuilder.show();
	    }

	    @Override
	    public boolean onKeyDown(int keyCode, KeyEvent event) {
	        switch (keyCode) {
	            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
	            case KeyEvent.KEYCODE_HEADSETHOOK:
	                startService(new Intent(MusicService.ACTION_TOGGLE_PLAYBACK));
	                return true;
	        }
	        return super.onKeyDown(keyCode, event);
	    }
	}