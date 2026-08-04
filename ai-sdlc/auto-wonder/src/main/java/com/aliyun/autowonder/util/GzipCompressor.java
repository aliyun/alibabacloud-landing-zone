package com.aliyun.autowonder.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class GzipCompressor {
    public static byte[] compress(byte[] in) {
        if (in == null) {
            throw new NullPointerException("Can't compress null");
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        GZIPOutputStream gz = null;

        try {
            gz = new GZIPOutputStream(bos);
            gz.write(in);
        } catch (IOException e) {
            throw new RuntimeException("IO exception compressing data(gzip)", e);
        } finally {
            try {
                if (gz != null) {
                    gz.close();
                }
                bos.close();
            } catch (Exception e) {
                // should not happen
            }
        }

        return bos.toByteArray();
    }

    public static byte[] decompress(byte[] in) {
        ByteArrayOutputStream bos = null;

        if (in != null) {
            ByteArrayInputStream bis = new ByteArrayInputStream(in);

            bos = new ByteArrayOutputStream();

            GZIPInputStream gis = null;

            try {
                gis = new GZIPInputStream(bis);

                byte[] buf = new byte[8192];
                int r = -1;

                while ((r = gis.read(buf)) > 0) {
                    bos.write(buf, 0, r);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                try {
                    if (gis != null) {
                        gis.close();
                    }
                    bis.close();
                    bos.close();
                } catch (Exception e) {
                    // should not happen
                }
            }
        }

        return (bos == null) ? null : bos.toByteArray();
    }
}
