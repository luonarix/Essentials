package dev.local.essentials;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** Transparent helper activity that returns a gallery image to the sidebar. */
public class RefImageActivity extends Activity {

    private static final int PICK = 41;
    private boolean launched;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, PICK);
            launched = true;
        } catch (Exception e) {
            try {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("image/*");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(i, PICK);
                launched = true;
            } catch (Exception ignored) {
                finish();
            }
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK && result == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                SidebarService.setReferenceImage(this, uri);
            }
        }
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!launched) finish();
    }
}
