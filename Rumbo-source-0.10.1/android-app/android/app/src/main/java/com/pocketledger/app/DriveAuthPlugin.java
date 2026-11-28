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
import com.google.android.gms.common.api.CommonStatusCodes;
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
            .addOnFailureListener(e -> call.reject(describe(e), "ERROR"));
    }

    /** Called by MainActivity when Google's sign-in screen closes. */
    void handleResult(int resultCode, Intent data) {
        PluginCall call = pending;
        pending = null;
        if (call == null) return;
        // Google often reports a failure as "cancelled" but puts the real reason in the returned data, so always read it.
        if (data == null) {
            if (resultCode == Activity.RESULT_OK) call.reject("Google returned no answer. Try again.", "ERROR");
            else call.reject("Sign-in was cancelled", "CANCELLED");
            return;
        }
        try {
            AuthorizationResult result = Identity.getAuthorizationClient(getActivity()).getAuthorizationResultFromIntent(data);
            if (resultCode != Activity.RESULT_OK && (result.getAccessToken() == null || result.getAccessToken().isEmpty())) {
                call.reject("Sign-in was cancelled (Google result " + resultCode + ", no error code given)", "ERROR");
                return;
            }
            resolveToken(call, result);
        } catch (ApiException e) {
            call.reject(describe(e), "ERROR");
        }
    }

    /** Google's own status code plus a plain-words hint, so a failed sign-in says why. */
    private static String describe(Exception e) {
        if (!(e instanceof ApiException)) return "Google sign-in failed: " + e.getMessage();
        int code = ((ApiException) e).getStatusCode();
        String name = CommonStatusCodes.getStatusCodeString(code);
        String hint;
        switch (code) {
            case 10: hint = "Rumbo's Android setup in Google Cloud does not match this app: check the Android OAuth client has package com.pocketledger.app and SHA-1 37:A1:CD:02:4B:5B:7C:DD:06:12:CF:84:C5:7A:D8:44:48:2D:59:9E."; break;
            case 16: hint = "Google stopped the request. Usually the consent screen was closed, or this Google account is not a test user of Rumbo."; break;
            case 12501: hint = "The sign-in screen was closed."; break;
            case 7: hint = "No internet connection reached Google. Check the connection and try again."; break;
            case 8: hint = "Google had an internal problem. Try again in a moment."; break;
            case 4: hint = "Google needs you to sign in to this account again on the phone."; break;
            default: hint = String.valueOf(((ApiException) e).getMessage());
        }
        return "Google sign-in failed (code " + code + " " + name + "). " + hint;
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
