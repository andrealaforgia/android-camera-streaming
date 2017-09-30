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

import java.io.IOException;
import java.io.OutputStream;

class MemoryOutputStream extends OutputStream {
    private final byte[] buffer;
    private int length = 0;

    MemoryOutputStream(final int size) {
        this(new byte[size]);
    }

    private MemoryOutputStream(final byte[] buffer) {
        super();
        this.buffer = buffer;
    }

    @Override
    public void write(final byte[] buffer, final int offset, final int count)
            throws IOException {
        checkSpace(count);
        System.arraycopy(buffer, offset, this.buffer, length, count);
        length += count;
    }

    @Override
    public void write(final byte[] buffer) throws IOException {
        checkSpace(buffer.length);
        System.arraycopy(buffer, 0, this.buffer, length, buffer.length);
        length += buffer.length;
    }

    @Override
    public void write(final int oneByte) throws IOException {
        checkSpace(1);
        buffer[length++] = (byte) oneByte;
    }

    private void checkSpace(final int length) throws IOException {
        if (this.length + length >= buffer.length) {
            throw new IOException("insufficient space in buffer");
        }
    }

    void seek(final int index) {
        length = index;
    }

    byte[] getBuffer() {
        return buffer;
    }

    int getLength() {
        return length;
    }
}

