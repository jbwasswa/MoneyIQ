package com.pocketledger.app;

import android.accounts.Account;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.UserRecoverableAuthException;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Scope;
import java.util.Collections;

/**
 * Google Drive access for Rumbo's backups. Asks Google for permission to the files Rumbo creates in the
 * user's own Drive (drive.file) and hands the screens a short-lived access token.
 *
 * Two routes:
 *  - the newer AuthorizationClient (tried first), and
 *  - the classic Google Sign-In, used automatically when the newer one fails with an internal error
 *    on a phone (seen as code 8). Once the classic route has worked, Rumbo keeps using it on that phone.
 */
@CapacitorPlugin(name = "RumboDrive")
@SuppressWarnings("deprecation") // the classic Google Sign-In is deprecated but still supported, and works where the new route fails
public class DriveAuthPlugin extends Plugin {

    static final int REQ = 47101;          // newer route's consent screen
    static final int REQ_CLASSIC = 47102;  // classic sign-in screen
    static final int REQ_RECOVER = 47103;  // classic route asking the user to approve Drive access
    private static final String SCOPE = "https://www.googleapis.com/auth/drive.file";
    private static final String PREFS = "rumbo-drive";
    private static final String KEY_CLASSIC = "classic";

    private PluginCall pending;
    private Account pendingAccount;     // classic route: the account whose token we are fetching
    private String firstError = "";     // newer route's error, shown if the classic one fails too

    // ---------- entry point ----------

    @PluginMethod
    public void token(PluginCall call) {
        final boolean interactive = Boolean.TRUE.equals(call.getBoolean("interactive", false));
        Activity activity = getActivity();
        if (activity == null) {
            call.reject("Rumbo is not in the foreground", "ERROR");
            return;
        }
        if (usesClassic()) {
            classicToken(call, interactive);
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
                    takePending(call);
                    try {
                        activity.startIntentSenderForResult(intent.getIntentSender(), REQ, null, 0, 0, 0);
                    } catch (IntentSender.SendIntentException e) {
                        pending = null;
                        call.reject("Could not open Google sign-in: " + e.getMessage(), "ERROR");
                    }
                } else {
                    resolveToken(call, result.getAccessToken());
                }
            })
            .addOnFailureListener(e -> {
                if (interactive && shouldFallBack(e)) startClassic(call, describe(e));
                else call.reject(describe(e), "ERROR");
            });
    }

    /** Called by MainActivity when one of Google's screens closes. */
    void handleResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ) handleNewRoute(resultCode, data);
        else if (requestCode == REQ_CLASSIC) handleClassicSignIn(resultCode, data);
        else if (requestCode == REQ_RECOVER) handleRecover(resultCode);
    }

    // ---------- newer route (AuthorizationClient) ----------

    private void handleNewRoute(int resultCode, Intent data) {
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
            String token = result.getAccessToken();
            if (resultCode != Activity.RESULT_OK && (token == null || token.isEmpty())) {
                call.reject("Sign-in was cancelled (Google result " + resultCode + ", no error code given)", "ERROR");
                return;
            }
            resolveToken(call, token);
        } catch (ApiException e) {
            if (shouldFallBack(e)) startClassic(call, describe(e));
            else call.reject(describe(e), "ERROR");
        }
    }

    /** Fall back on Google's own failures, not when the user simply closed the screen. */
    private static boolean shouldFallBack(Exception e) {
        if (!(e instanceof ApiException)) return false;
        int code = ((ApiException) e).getStatusCode();
        return code != 12501 && code != 7; // 12501 = closed by the user, 7 = no internet (the classic route would fail too)
    }

    // ---------- classic route (Google Sign-In) ----------

    private GoogleSignInClient classicClient() {
        GoogleSignInOptions options = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(new Scope(SCOPE))
            .build();
        return GoogleSignIn.getClient(getActivity(), options);
    }

    private void startClassic(PluginCall call, String newRouteError) {
        Activity activity = getActivity();
        if (activity == null) {
            call.reject(newRouteError, "ERROR");
            return;
        }
        firstError = newRouteError;
        takePending(call);
        activity.runOnUiThread(() -> {
            try {
                activity.startActivityForResult(classicClient().getSignInIntent(), REQ_CLASSIC);
            } catch (Exception e) {
                PluginCall c = pending;
                pending = null;
                if (c != null) c.reject(firstError + " The classic sign-in could not open: " + e.getMessage(), "ERROR");
            }
        });
    }

    private void handleClassicSignIn(int resultCode, Intent data) {
        PluginCall call = pending;
        if (call == null) return;
        try {
            GoogleSignInAccount account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException.class);
            if (account == null || account.getAccount() == null) {
                pending = null;
                call.reject("Google did not return an account. Try again.", "ERROR");
                return;
            }
            fetchClassicToken(call, account.getAccount(), true);
        } catch (ApiException e) {
            pending = null;
            int code = e.getStatusCode();
            if (code == 12501) call.reject("Sign-in was cancelled", "CANCELLED");
            else call.reject("Both Google sign-in routes failed. New route: " + firstError + " Classic route: " + describe(e), "ERROR");
        }
    }

    /** Later calls on a phone that uses the classic route. */
    private void classicToken(PluginCall call, boolean interactive) {
        GoogleSignInAccount last = GoogleSignIn.getLastSignedInAccount(getContext());
        if (last == null || last.getAccount() == null || !GoogleSignIn.hasPermissions(last, new Scope(SCOPE))) {
            if (!interactive) {
                call.reject("Sign in to Google again", "AUTH");
                return;
            }
            startClassic(call, "");
            return;
        }
        if (interactive) takePending(call);
        fetchClassicToken(call, last.getAccount(), interactive);
    }

    /** GoogleAuthUtil must not run on the main thread. */
    private void fetchClassicToken(PluginCall call, Account account, boolean interactive) {
        final Context ctx = getContext();
        new Thread(() -> {
            try {
                String token = GoogleAuthUtil.getToken(ctx, account, "oauth2:" + SCOPE);
                if (pending == call) pending = null;
                setClassic(true);
                resolveToken(call, token);
            } catch (UserRecoverableAuthException e) {
                Intent intent = e.getIntent();
                Activity activity = getActivity();
                if (!interactive || intent == null || activity == null) {
                    if (pending == call) pending = null;
                    call.reject("Sign in to Google again", "AUTH");
                    return;
                }
                pendingAccount = account;
                pending = call;
                activity.runOnUiThread(() -> activity.startActivityForResult(intent, REQ_RECOVER));
            } catch (Exception e) {
                if (pending == call) pending = null;
                call.reject("Could not get Drive access from Google: " + e.getMessage(), "ERROR");
            }
        }).start();
    }

    private void handleRecover(int resultCode) {
        PluginCall call = pending;
        Account account = pendingAccount;
        pendingAccount = null;
        if (call == null) return;
        if (resultCode != Activity.RESULT_OK || account == null) {
            pending = null;
            call.reject("Sign-in was cancelled", "CANCELLED");
            return;
        }
        fetchClassicToken(call, account, false);
    }

    // ---------- shared ----------

    private void takePending(PluginCall call) {
        if (pending != null && pending != call) pending.reject("Another sign-in started", "CANCELLED");
        pending = call;
    }

    private void resolveToken(PluginCall call, String token) {
        if (token == null || token.isEmpty()) {
            call.reject("Google did not give access to Drive", "AUTH");
            return;
        }
        JSObject out = new JSObject();
        out.put("accessToken", token);
        call.resolve(out);
    }

    private boolean usesClassic() {
        return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CLASSIC, false);
    }

    private void setClassic(boolean on) {
        SharedPreferences.Editor ed = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        ed.putBoolean(KEY_CLASSIC, on).apply();
    }

    /** Google's own status code, a plain-words hint, and Google's raw message, so a failed sign-in says why. */
    private static String describe(Exception e) {
        if (!(e instanceof ApiException)) return "Google sign-in failed: " + e.getMessage();
        ApiException ae = (ApiException) e;
        int code = ae.getStatusCode();
        String name = CommonStatusCodes.getStatusCodeString(code);
        String hint;
        switch (code) {
            case 10: hint = "Rumbo's Android setup in Google Cloud does not match this app: check the Android OAuth client has package com.pocketledger.app and SHA-1 37:A1:CD:02:4B:5B:7C:DD:06:12:CF:84:C5:7A:D8:44:48:2D:59:9E."; break;
            case 16: hint = "Google stopped the request. Usually the consent screen was closed, or this Google account is not a test user of Rumbo."; break;
            case 12501: hint = "The sign-in screen was closed."; break;
            case 7: hint = "No internet connection reached Google. Check the connection and try again."; break;
            case 8: hint = "Google had an internal problem."; break;
            case 4: hint = "Google needs you to sign in to this account again on the phone."; break;
            default: hint = "";
        }
        String raw = String.valueOf(ae.getMessage());
        return "Google sign-in failed (code " + code + " " + name + "). " + hint + (raw.isEmpty() ? "" : " [Google: " + raw + "]");
    }

    // ---------- tokens and sign-out ----------

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

    /** Disconnect: also signs out of the classic route, so the next sign-in shows the account picker again. */
    @PluginMethod
    public void signOut(PluginCall call) {
        setClassic(false);
        if (getActivity() == null) {
            call.resolve();
            return;
        }
        try {
            classicClient().signOut().addOnCompleteListener(t -> call.resolve());
        } catch (Exception e) {
            call.resolve();
        }
    }
}
