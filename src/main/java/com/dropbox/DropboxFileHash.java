package com.dropbox;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * Calculates hash for file used by DropBox.
 * As by this implementation: https://github.com/dropbox/dropbox-api-content-hasher
 */
public class DropboxFileHash {

    private static final char[] HEX_DIGITS = new char[]{
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            'a', 'b', 'c', 'd', 'e', 'f'};

    private MessageDigest hasher = new DropboxContentHasher();

    public DropboxFileHash(File f) throws IOException {
        byte[] buf = new byte[1024];
        try (InputStream in = new FileInputStream(f)) {
            while (true) {
                int n = in.read(buf);
                if (n < 0) break;  // EOF
                hasher.update(buf, 0, n);
            }
        }

    }

    public String asHex() {
        byte[] data = hasher.digest();
        char[] buf = new char[2 * data.length];
        int i = 0;
        for (byte b : data) {
            buf[i++] = HEX_DIGITS[(b & 0xf0) >>> 4];
            buf[i++] = HEX_DIGITS[b & 0x0f];
        }
        return new String(buf);
    }
}
