package com.github.teocci.ffmpeg.recorder.data;

import org.bytedeco.javacv.Frame;

/**
 * Created by teocci.
 *
 * @author teocci@yandex.com on 2017-May-19
 */

public class FrameToRecord
{
    private long timestamp;
    private Frame frame;

    public FrameToRecord(long timestamp, Frame frame)
    {
        this.timestamp = timestamp;
        this.frame = frame;
    }

    public long getTimestamp()
    {
        return timestamp;
    }

    public void setTimestamp(long timestamp)
    {
        this.timestamp = timestamp;
    }

    public Frame getFrame()
    {
        return frame;
    }

    public void setFrame(Frame frame)
    {
        this.frame = frame;
    }
}
