package io.github.mohammedshajith.rewardswatcher;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Sign in to Microsoft Rewards inside the app. Uses Android's built-in
 * WebView (no extra size); it's only loaded on this screen, never during the
 * background checks. "Done" copies the cookies into the app's session file.
 */
public class SignInActivity extends Activity {

    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        int pad = Math.round(12 * getResources().getDisplayMetrics().density);
        bar.setPadding(pad, pad, pad, pad);
        TextView hint = new TextView(this);
        hint.setText("Sign in to Microsoft Rewards. When you can see your points, tap Done.");
        hint.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button done = new Button(this);
        done.setText("Done");
        done.setAllCaps(false);
        done.setOnClickListener(v -> finishSignIn());
        bar.addView(hint);
        bar.addView(done);
        col.addView(bar);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);      // the Microsoft login page needs it
        s.setDomStorageEnabled(true);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient()); // keep navigation inside the app
        web.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        col.addView(web);

        col.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(col);
        web.loadUrl(Checker.BASE + "/redeem/" + State.country(this));
    }

    private void finishSignIn() {
        CookieManager cm = CookieManager.getInstance();
        cm.flush();
        Cookies jar = new Cookies();
        String shop = Checker.BASE + "/redeem/" + State.country(this) + "/shop";
        jar.addFromHeader("rewards.bing.com", cm.getCookie(shop));
        jar.addFromHeader("login.live.com", cm.getCookie("https://login.live.com/"));
        if (!jar.hasDomain("rewards.bing.com")) {
            Toast.makeText(this, "Not signed in yet. Finish signing in first.", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Checking the sign-in…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg;
            try {
                State.write(this, State.SESSION, jar.toJson());
                State.prefs(this).edit().putBoolean("warnedSignedOut", false).apply();
                Checker.run(getApplicationContext(), false);
                String r = State.prefs(this).getString("lastResult", "");
                msg = r.contains("items") ? "Signed in ✓ (" + r + ")" : "Sign-in didn't work: " + r;
            } catch (Exception e) {
                msg = "Couldn't save sign-in: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> {
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                if (m.startsWith("Signed in")) {
                    web.clearCache(true); // keep storage small
                    finish();
                }
            });
        }).start();
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
