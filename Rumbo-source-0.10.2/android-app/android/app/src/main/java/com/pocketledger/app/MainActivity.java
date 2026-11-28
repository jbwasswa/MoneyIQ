package com.pocketledger.app;

import android.content.Intent;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.PluginHandle;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(DriveAuthPlugin.class);
        super.onCreate(savedInstanceState);
    }

    // Google's sign-in screen reports back here; hand the result to the Drive plugin.
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        boolean ours = requestCode == DriveAuthPlugin.REQ || requestCode == DriveAuthPlugin.REQ_CLASSIC
            || requestCode == DriveAuthPlugin.REQ_RECOVER;
        if (!ours || getBridge() == null) return;
        PluginHandle handle = getBridge().getPlugin("RumboDrive");
        if (handle != null && handle.getInstance() instanceof DriveAuthPlugin) {
            ((DriveAuthPlugin) handle.getInstance()).handleResult(requestCode, resultCode, data);
        }
    }
}
