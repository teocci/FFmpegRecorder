package com.github.teocci.ffmpeg.recorder.data;

/**
 * Created by teocci.
 *
 * @author teocci@yandex.com on 2017-May-19
 */

public class ChunkRecord
{
    private long startTimestamp;
    private long endTimestamp;

    public void setStartTimestamp(long startTimestamp)
    {
        this.startTimestamp = startTimestamp;
    }

    public long getStartTimestamp()
    {
        return startTimestamp;
    }

    public void setEndTimestamp(long endTimestamp)
    {
        this.endTimestamp = endTimestamp;
    }

    public long getEndTimestamp()
    {
        return endTimestamp;
    }

    public long getDuration()
    {
        return endTimestamp - startTimestamp;
    }
}
