/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.managedprovisioning.preprovisioning;

import static com.android.internal.logging.nano.MetricsProto.MetricsEvent
        .PROVISIONING_WEB_ACTIVITY_TIME_MS;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnLongClickListener;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.android.managedprovisioning.analytics.TimeLogger;
import com.android.managedprovisioning.common.ProvisionLogger;
import com.android.managedprovisioning.common.SettingsFacade;
import com.android.managedprovisioning.preprovisioning.terms.TermsActivity;

/**
 * This activity shows a web view, which loads the url indicated in the starting intent. By default
 * the user can click on links and load other urls. However, by passing the allowed url base, the
 * web view can be limited to urls that start with this base.
 *
 * <p>This activity is considered for using by
 * {@link TermsActivity} to display the support web pages
 * about provisioning concepts.
 *
 * TODO: remove if not used by 2017-Jan-27 -- http://b/33811446
 */
public class WebActivity extends Activity {
    private static final String EXTRA_URL = "extra_url";

    // Users can only browse urls starting with the base specified by the following extra.
    // If this extra is not used, there are no restrictions on browsable urls.
    private static final String EXTRA_ALLOWED_URL_BASE = "extra_allowed_url_base";

    private WebView mWebView;
    private final SettingsFacade mSettingsFacade = new SettingsFacade();
    private TimeLogger mTimeLogger;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mWebView = new WebView(this);
        mTimeLogger = new TimeLogger(this, PROVISIONING_WEB_ACTIVITY_TIME_MS);

        final String extraUrl = getIntent().getStringExtra(EXTRA_URL);
        final String extraAllowedUrlBase = getIntent().getStringExtra(EXTRA_ALLOWED_URL_BASE);
        if (extraUrl == null) {
            ProvisionLogger.loge("No url provided to WebActivity.");
            finish();
            return;
        }
        mWebView.loadUrl(extraUrl);
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (extraAllowedUrlBase != null && url.startsWith(extraAllowedUrlBase)) {
                    view.loadUrl(url);
                }
                return true;
            }
        });
        if (!mSettingsFacade.isUserSetupCompleted(this)) {
            // User should not be able to escape provisioning if user setup isn't complete.
            mWebView.setOnLongClickListener(new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    return true;
                }
            });
        }
        mTimeLogger.start();
        this.setContentView(mWebView);
    }

    @Override
    public void onDestroy() {
        mTimeLogger.stop();
        super.onDestroy();
    }

    /**
     * Creates an intent to launch the webactivity.
     *
     * @param url the url to be shown upon launching this activity
     * @param allowedUrlBase the limit to all urls allowed to be seen in this webview
     */
    public static Intent createIntent(Context context, String url, String allowedUrlBase) {
        Intent intent = new Intent(context, WebActivity.class);
        intent.putExtra(WebActivity.EXTRA_URL, url);
        intent.putExtra(WebActivity.EXTRA_ALLOWED_URL_BASE, allowedUrlBase);
        return intent;
    }
}