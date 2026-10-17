package android.media;

import android.graphics.PixelFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Minimal acquired Image with real pixel bytes; Android's Image/Plane constructors are package private. */
public final class FrameFixtureImage extends Image {
    private final Plane[] planes;
    public volatile boolean closed;

    public FrameFixtureImage(int color) {
        ByteBuffer pixels = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
        pixels.putInt(color).rewind();
        planes = new Plane[] { new FixturePlane(pixels) };
    }
    @Override public void close() { closed = true; }
    @Override public int getFormat() { return PixelFormat.RGBA_8888; }
    @Override public int getWidth() { return 1; }
    @Override public int getHeight() { return 1; }
    @Override public long getTimestamp() { return 0; }
    @Override public Plane[] getPlanes() {
        if (closed) throw new IllegalStateException("image already closed");
        return planes;
    }
    private static final class FixturePlane extends Plane {
        private final ByteBuffer pixels;
        FixturePlane(ByteBuffer pixels) { this.pixels = pixels; }
        @Override public ByteBuffer getBuffer() { return pixels; }
        @Override public int getPixelStride() { return 4; }
        @Override public int getRowStride() { return 4; }
    }
}
