package com.pocketledger.app;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentSender;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import java.util.Collections;

/**
 * Google Drive access for Rumbo's backups. Asks Google for permission to the files Rumbo creates in the
 * user's own Drive (drive.file) and hands the screens a short-lived access token. The first time Google shows
 * its account picker and consent screen; after that tokens come back silently.
 */
@CapacitorPlugin(name = "RumboDrive")
public class DriveAuthPlugin extends Plugin {

    static final int REQ = 47101;
    private static final String SCOPE = "https://www.googleapis.com/auth/drive.file";
    private PluginCall pending;

    @PluginMethod
    public void token(PluginCall call) {
        final boolean interactive = Boolean.TRUE.equals(call.getBoolean("interactive", false));
        Activity activity = getActivity();
        if (activity == null) {
            call.reject("Rumbo is not in the foreground", "ERROR");
            return;
        }
        AuthorizationRequest request = AuthorizationRequest.builder()
            .setRequestedScopes(Collections.singletonList(new Scope(SCOPE)))
            .build();
        Identity.getAuthorizationClient(activity)
            .authorize(request)
            .addOnSuccessListener(result -> {
                if (result.hasResolution()) {
                    if (!interactive) {
                        call.reject("Sign in to Google again", "AUTH");
                        return;
                    }
                    PendingIntent intent = result.getPendingIntent();
                    if (intent == null) {
                        call.reject("Google did not offer a sign-in screen", "ERROR");
                        return;
                    }
                    if (pending != null) pending.reject("Another sign-in started", "CANCELLED");
                    pending = call;
                    try {
                        activity.startIntentSenderForResult(intent.getIntentSender(), REQ, null, 0, 0, 0);
                    } catch (IntentSender.SendIntentException e) {
                        pending = null;
                        call.reject("Could not open Google sign-in: " + e.getMessage(), "ERROR");
                    }
                } else {
                    resolveToken(call, result);
                }
            })
            .addOnFailureListener(e -> call.reject("Google sign-in failed: " + e.getMessage(), "ERROR"));
    }

    /** Called by MainActivity when Google's sign-in screen closes. */
    void handleResult(int resultCode, Intent data) {
        PluginCall call = pending;
        pending = null;
        if (call == null) return;
        if (resultCode != Activity.RESULT_OK || data == null) {
            call.reject("Sign-in was cancelled", "CANCELLED");
            return;
        }
        try {
            AuthorizationResult result = Identity.getAuthorizationClient(getActivity()).getAuthorizationResultFromIntent(data);
            resolveToken(call, result);
        } catch (ApiException e) {
            call.reject("Google sign-in failed: " + e.getMessage(), "ERROR");
        }
    }

    private void resolveToken(PluginCall call, AuthorizationResult result) {
        String token = result.getAccessToken();
        if (token == null || token.isEmpty()) {
            call.reject("Google did not give access to Drive", "AUTH");
            return;
        }
        JSObject out = new JSObject();
        out.put("accessToken", token);
        call.resolve(out);
    }

    /** Forgets a token Google rejected, so the next token() call fetches a fresh one. */
    @PluginMethod
    public void clear(PluginCall call) {
        final String token = call.getString("token");
        new Thread(() -> {
            try {
                if (token != null && !token.isEmpty()) GoogleAuthUtil.clearToken(getContext(), token);
            } catch (Exception ignored) {
                // nothing cached, or already cleared
            }
            call.resolve();
        }).start();
    }
}
