package com.lilt.app;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

/** Test-only SAF provider; one document kind refuses writes to exercise permission failures. */
public class DeletionDocumentsProvider extends DocumentsProvider {
    private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE};

    private File directory() {
        File directory = new File(getContext().getCacheDir(), "deletion-documents");
        directory.mkdirs();
        return directory;
    }

    @Override public boolean onCreate() { return true; }
    @Override public Cursor queryRoots(String[] projection) {
        return new MatrixCursor(projection == null ? new String[0] : projection);
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sort) {
        return new MatrixCursor(projection == null ? COLUMNS : projection);
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? COLUMNS : projection);
        File file = new File(directory(), id);
        if (!id.equals("root") && !file.exists()) throw new FileNotFoundException(id);
        int flags = id.equals("root") ? Document.FLAG_DIR_SUPPORTS_CREATE
            : id.startsWith("readonly-") ? Document.FLAG_SUPPORTS_RENAME : Document.FLAG_SUPPORTS_DELETE;
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            Object value = null;
            if (column.equals(Document.COLUMN_DOCUMENT_ID)) value = id;
            else if (column.equals(Document.COLUMN_DISPLAY_NAME)) value = file.getName();
            else if (column.equals(Document.COLUMN_MIME_TYPE))
                value = id.equals("root") ? Document.MIME_TYPE_DIR : "audio/wav";
            else if (column.equals(Document.COLUMN_FLAGS)) value = flags;
            else if (column.equals(Document.COLUMN_SIZE)) value = file.length();
            row.add(value);
        }
        return cursor;
    }
    @Override public String createDocument(String parent, String mimeType, String displayName) throws FileNotFoundException {
        String id = (displayName.startsWith("readonly") ? "readonly-" : "writable-") + UUID.randomUUID() + ".wav";
        try (FileOutputStream stream = new FileOutputStream(new File(directory(), id))) {
            stream.write(new byte[] {82, 73, 70, 70});
        } catch (IOException error) {
            throw new FileNotFoundException(error.getMessage());
        }
        return id;
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException {
        if (id.startsWith("readonly-")) throw new SecurityException("This document is read-only");
        if (!new File(directory(), id).delete()) throw new FileNotFoundException(id);
    }
    @Override public String renameDocument(String oldId, String displayName) throws FileNotFoundException {
        String id = "writable-" + UUID.randomUUID() + ".wav";
        if (!new File(directory(), oldId).renameTo(new File(directory(), id))) throw new FileNotFoundException(oldId);
        return id;
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        return ParcelFileDescriptor.open(new File(directory(), id), ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
