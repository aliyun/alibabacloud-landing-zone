package com.aliyun.autowonder.artifact;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds genuine archive fixtures so the tests exercise the same
 * {@code java.util.zip} code path production validation uses.
 */
final class TestArchives {

    private static final byte[] OLE2_SIGNATURE = {
            (byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, (byte) 0x1A, (byte) 0xE1};

    /** {@code java.util.zip.ZipInputStream.LOCHDR}: signature, version, flags, sizes, name length. */
    private static final int LOCAL_FILE_HEADER_LENGTH = 30;

    /** Offset of the general purpose bit flag inside a local file header; bit 0 means encrypted. */
    private static final int LOCAL_FILE_HEADER_FLAG_OFFSET = 6;

    private TestArchives() {
    }

    static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue());
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static byte[] zipOf(String entryName, byte[] content) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(entryName, content);
        return zip(entries);
    }

    static byte[] docx(String bodyText) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml",
                "<?xml version=\"1.0\"?><Types/>".getBytes(StandardCharsets.UTF_8));
        entries.put("word/document.xml", ("<?xml version=\"1.0\"?><w:document><w:body><w:p><w:t>"
                + bodyText + "</w:t></w:p></w:body></w:document>").getBytes(StandardCharsets.UTF_8));
        return zip(entries);
    }

    /** Legacy .doc: only the OLE2 compound-file signature is validated, never the stream content. */
    static byte[] doc() {
        byte[] bytes = new byte[512];
        System.arraycopy(OLE2_SIGNATURE, 0, bytes, 0, OLE2_SIGNATURE.length);
        return bytes;
    }

    /** A no-entry archive carries only the end-of-central-directory record and is still legal. */
    static byte[] emptyZip() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.finish();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static byte[] zipWithManyEntries(int count) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] body = "x".getBytes(StandardCharsets.UTF_8);
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (int i = 0; i < count; i++) {
                zos.putNextEntry(new ZipEntry("entry-" + i + ".txt"));
                zos.write(body);
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static byte[] zipWithNestedPath(int depth) {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < depth - 1; i++) {
            name.append('d').append(i).append('/');
        }
        name.append("leaf.txt");
        return zipOf(name.toString(), "leaf".getBytes(StandardCharsets.UTF_8));
    }

    /** Zeros deflate to almost nothing, so the payload stays small while inflating past the guard. */
    static byte[] zipBomb(int entries, int inflatedBytesPerEntry) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] zeros = new byte[inflatedBytesPerEntry];
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (int i = 0; i < entries; i++) {
                zos.putNextEntry(new ZipEntry("bomb-" + i + ".bin"));
                zos.write(zeros);
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** Entry name of {@link #truncatedZip()}; distinctive so tests can prove it is never echoed back. */
    static final String TRUNCATED_ENTRY_NAME = "leaked-entry-name.txt";

    /**
     * A real archive cut off inside its entry name. This is the only truncation that reaches the
     * corrupt-archive fallback: {@code ZipInputStream.readLOC} swallows an {@code EOFException} from
     * the fixed 30-byte local header and returns null, but the entry-name read is unguarded, so
     * stopping mid-name makes {@code getNextEntry()} throw instead of reporting an empty archive.
     */
    static byte[] truncatedZip() {
        byte[] full = zipOf(TRUNCATED_ENTRY_NAME, "payload".getBytes(StandardCharsets.UTF_8));
        return Arrays.copyOf(full, LOCAL_FILE_HEADER_LENGTH + 2);
    }

    /**
     * Marks the entry encrypted, the shape a password-protected upload really has.
     * {@code getNextEntry()} rejects it before the payload is ever inflated.
     */
    static byte[] encryptedZip() {
        byte[] encrypted = zipOf("secret.txt", "payload".getBytes(StandardCharsets.UTF_8));
        encrypted[LOCAL_FILE_HEADER_FLAG_OFFSET] |= 0x01;
        return encrypted;
    }

    /** Carries an explicit {@code word/} directory entry, the shape OOXML writers really emit. */
    static byte[] docxWithDirectoryEntry(String bodyText) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zos.write("<?xml version=\"1.0\"?><Types/>".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("word/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("word/document.xml"));
            zos.write(("<w:document><w:body><w:p><w:t>" + bodyText
                    + "</w:t></w:p></w:body></w:document>").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /**
     * Sits exactly at the entry cap but adds directory entries on top, pinning that directories are
     * counted as entries; were they skipped by the counter this archive would be accepted.
     */
    static byte[] zipWithDirectoryEntriesAtTheEntryLimit(int fileEntries, int directoryEntries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] body = "x".getBytes(StandardCharsets.UTF_8);
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (int i = 0; i < fileEntries; i++) {
                zos.putNextEntry(new ZipEntry("entry-" + i + ".txt"));
                zos.write(body);
                zos.closeEntry();
            }
            for (int i = 0; i < directoryEntries; i++) {
                zos.putNextEntry(new ZipEntry("dir-" + i + "/"));
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
