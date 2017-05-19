package com.github.teocci.ffmpeg.recorder;

import android.media.MediaPlayer;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import com.github.teocci.ffmpeg.R;

/**
 * Created by teocci.
 *
 * @author teocci@yandex.com on 2017-May-19
 */
public class PlaybackActivity extends AppCompatActivity
{
    public static final String INTENT_NAME_VIDEO_PATH = "INTENT_NAME_VIDEO_PATH";

    private VideoView videoPlayback;

    private int currentPosition;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_playback);
        TextView tvVideoPath = (TextView) findViewById(R.id.tv_video_path);
        videoPlayback = (VideoView) findViewById(R.id.vv_playback);

        String path = getIntent().getStringExtra(INTENT_NAME_VIDEO_PATH);
        if (path == null) {
            finish();
        }

        tvVideoPath.setText(path);
        videoPlayback.setVideoPath(path);
        videoPlayback.setKeepScreenOn(true);
        videoPlayback.setMediaController(new MediaController(this));
        videoPlayback.setOnCompletionListener(new MediaPlayer.OnCompletionListener()
        {

            @Override
            public void onCompletion(MediaPlayer mp)
            {
            }
        });
        videoPlayback.start();
    }

    @Override
    protected void onDestroy()
    {
        super.onDestroy();
        videoPlayback.stopPlayback();
    }

    @Override
    protected void onPause()
    {
        super.onPause();
        videoPlayback.pause();
        currentPosition = videoPlayback.getCurrentPosition();
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        videoPlayback.seekTo(currentPosition);
        videoPlayback.start();
    }
}
