package com.foxdogstudios.peepers;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;

class Messenger {
    private final Looper looper;
    private final Handler handler;
    private final MessageListener messageListener;

    Messenger(String tag, MessageListener messageListener) {
        this.messageListener = messageListener;
        final HandlerThread worker = new HandlerThread(tag, Process.THREAD_PRIORITY_MORE_FAVORABLE);
        worker.setDaemon(true);
        worker.start();
        this.looper = worker.getLooper();
        this.handler = new WorkHandler(looper);
    }

    void sendMessage(int messageId) {
        handler.obtainMessage(messageId).sendToTarget();
    }

    void sendMessage(int messageId, Object content) {
        Message message = handler.obtainMessage(messageId);
        message.obj = content;
        message.sendToTarget();
    }

    void close() {
        looper.quit();
    }

    private class WorkHandler extends Handler {
        private WorkHandler(final Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(final Message message) {
            if (messageListener != null) {
                messageListener.onMessage(message);
            }
        }
    }
}
