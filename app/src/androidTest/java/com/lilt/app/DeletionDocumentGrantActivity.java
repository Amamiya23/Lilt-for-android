package com.lilt.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.net.Uri;

/** Runs under the test APK's UID, which owns the provider and may grant URI access. */
public class DeletionDocumentGrantActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        grantUriPermission("com.lilt.app",
            Uri.parse("content://com.lilt.app.test.deletion.documents/"),
            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        finish();
    }
}
