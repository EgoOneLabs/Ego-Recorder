package com.serenegiant.usb;

import java.nio.ByteBuffer;

/** Original MJPEG bytes from libuvc, before any RGBX conversion or preview frame loss. */
public interface IRawFrameCallback {
    /** Buffer lifetime ends when this synchronous method returns; copy any retained bytes. */
    void onFrame(ByteBuffer jpeg, long receiveBootNs, long uvcSequence, int width, int height);
}
