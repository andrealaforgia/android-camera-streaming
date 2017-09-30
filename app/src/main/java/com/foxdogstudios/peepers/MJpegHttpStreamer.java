/* Copyright 2013 Foxdog Studios Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.foxdogstudios.peepers;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

class MJpegHttpStreamer {
    private static final String TAG = MJpegHttpStreamer.class.getSimpleName();

    private static final String BOUNDARY = "--gc0p4Jq0M2Yt08jU534c0p--";
    private static final String BOUNDARY_LINES = "\r\n" + BOUNDARY + "\r\n";

    private static final String HTTP_HEADER =
            "HTTP/1.0 200 OK\r\n"
                    + "Server: Peepers\r\n"
                    + "Connection: close\r\n"
                    + "Max-Age: 0\r\n"
                    + "Expires: 0\r\n"
                    + "Cache-Control: no-store, no-cache, must-revalidate, pre-check=0, "
                    + "post-check=0, max-age=0\r\n"
                    + "Pragma: no-cache\r\n"
                    + "Access-Control-Allow-Origin:*\r\n"
                    + "Content-Type: multipart/x-mixed-replace; "
                    + "boundary=" + BOUNDARY + "\r\n"
                    + BOUNDARY_LINES;

    private final int port;

    private boolean newJpeg = false;
    private boolean streamingBufferA = true;
    private final byte[] bufferA;
    private final byte[] bufferB;
    private int lengthA = Integer.MIN_VALUE;
    private int lengthB = Integer.MIN_VALUE;
    private long timestampA = Long.MIN_VALUE;
    private long timestampB = Long.MIN_VALUE;
    private final Object bufferLock = new Object();

    private Thread worker = null;
    private volatile boolean isRunning = false;

    MJpegHttpStreamer(final int port, final int bufferSize) {
        super();
        this.port = port;
        bufferA = new byte[bufferSize];
        bufferB = new byte[bufferSize];
    }

    void start() {
        if (isRunning) {
            throw new IllegalStateException("MJpegHttpStreamer is already running");
        }

        isRunning = true;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                workerRun();
            }
        });
        worker.start();
    }

    void stop() {
        if (!isRunning) {
            throw new IllegalStateException("MJpegHttpStreamer is already stopped");
        }

        isRunning = false;
        worker.interrupt();
    }

    void streamJpeg(final byte[] jpeg, final int length, final long timestamp) {
        synchronized (bufferLock) {
            final byte[] buffer;
            if (streamingBufferA) {
                buffer = bufferB;
                lengthB = length;
                timestampB = timestamp;
            } else {
                buffer = bufferA;
                lengthA = length;
                timestampA = timestamp;
            }
            System.arraycopy(jpeg, 0, buffer, 0, length);
            newJpeg = true;
            bufferLock.notify();
        }
    }

    private void workerRun() {
        while (isRunning) {
            try {
                acceptAndStream();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void acceptAndStream() throws IOException {
        ServerSocket serverSocket = null;
        Socket socket = null;
        DataOutputStream stream = null;

        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setSoTimeout(1000 /* milliseconds */);

            do {
                try {
                    socket = serverSocket.accept();
                } catch (SocketTimeoutException e) {
                    if (!isRunning) {
                        return;
                    }
                }
            } while (socket == null);

            serverSocket.close();
            serverSocket = null;
            stream = new DataOutputStream(socket.getOutputStream());
            stream.writeBytes(HTTP_HEADER);
            stream.flush();

            while (isRunning) {
                final byte[] buffer;
                final int length;
                final long timestamp;

                synchronized (bufferLock) {
                    while (!newJpeg) {
                        try {
                            bufferLock.wait();
                        } catch (InterruptedException e) {
                            // stop() may have been called
                            return;
                        }
                    }

                    streamingBufferA = !streamingBufferA;

                    if (streamingBufferA) {
                        buffer = bufferA;
                        length = lengthA;
                        timestamp = timestampA;
                    } else {
                        buffer = bufferB;
                        length = lengthB;
                        timestamp = timestampB;
                    }

                    newJpeg = false;
                }

                stream.writeBytes(
                        "Content-type: image/jpeg\r\n"
                                + "Content-Length: " + length + "\r\n"
                                + "X-Timestamp:" + timestamp + "\r\n"
                                + "\r\n"
                );
                stream.write(buffer, 0 /* offset */, length);
                stream.writeBytes(BOUNDARY_LINES);
                stream.flush();
            }
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}

