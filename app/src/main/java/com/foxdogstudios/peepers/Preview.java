package com.foxdogstudios.peepers;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.Camera;

class Preview {

    private final Rect previewRect;
    private final int format;
    private final int bufferSize;
    private final int width;
    private final int height;

    Preview(final Camera.Parameters params) {
        // Set up preview callback
        format = params.getPreviewFormat();
        final Camera.Size previewSize = params.getPreviewSize();
        width = previewSize.width;
        height = previewSize.height;
        final int bytesPerPixel = ImageFormat.getBitsPerPixel(format) / 8;
        // According to the documentation the buffer size can be
        // calculated by width * height * bytesPerPixel. However, this
        // returned an error saying it was too small. It always needed
        // to be exactly 1.5 times larger.
        bufferSize = width * height * bytesPerPixel * 3 / 2 + 1;
        previewRect = new Rect(0, 0, width, height);
    }

    int getBufferSize() {
        return bufferSize;
    }

    int getFormat() {
        return format;
    }

    Rect getRect() {
        return previewRect;
    }

    int getWidth() {
        return width;
    }

    int getHeight() {
        return height;
    }
}
