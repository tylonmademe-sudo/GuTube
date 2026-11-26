package com.newtube.mobile.ui.webbrowser;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.browse.PortalAdBlocker;
import com.newtube.mobile.ui.common.MobileActivity;

import java.util.Locale;

/**
 * GUTUBE(portal): 100% fullscreen in-app WebView portal with zero browser chrome,
 * HTML5 fullscreen video support, and 3-layer AdGuard/OISD ad/popunder/cosmetic blocking.
 */
public class MobileWebPortalActivity extends MobileActivity {

    private static final String EXTRA_URL = "portal:url";
    private static final String CHROME_MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

    private static final String[] REDIRECT_TRACKER_PATTERNS = {
            "/go/", "/out/", "/clk", "/click/", "/redirect", "/redir",
            "/track/", "/aff/", "/exit/", "?aff=", "?zoneid=", "?clickid=", "?subid="
    };

    private static final String SHIELD_JS =
            "(function(){"
            + "try{"
            + "if(!window.__gtOpenLocked){"
            + "window.__gtOpenLocked=true;"
            + "Object.defineProperty(window,'open',{value:function(){return null;},configurable:false,writable:false});"
            + "}"
            + "}catch(e){try{window.open=function(){return null;};}catch(e2){}}"
            + "try{"
            + "if(!window.__gtDomHooked){"
            + "window.__gtDomHooked=true;"
            + "var adKw=['exoclick','exosrv','realsrv','magsrv','tsyndicate','juicyads','trafficjunky',"
            + "'popads','popcash','adsterra','propeller','monetag','hilltopads','plugrush','clickadu',"
            + "'doubleclick','googlesyndication','chaturbate','stripchat','livejasmin','bongacams'];"
            + "function isAdNode(el){"
            + "if(!el||!el.tagName)return false;"
            + "var t=el.tagName.toUpperCase();"
            + "if(t!=='SCRIPT'&&t!=='IFRAME')return false;"
            + "var s=((el.getAttribute&&el.getAttribute('src'))||el.src||'').toLowerCase();"
            + "if(!s)return false;"
            + "for(var i=0;i<adKw.length;i++){if(s.indexOf(adKw[i])!==-1)return true;}"
            + "return false;"
            + "}"
            + "var origAppend=Element.prototype.appendChild;"
            + "Element.prototype.appendChild=function(n){return isAdNode(n)?n:origAppend.call(this,n);};"
            + "var origInsert=Element.prototype.insertBefore;"
            + "Element.prototype.insertBefore=function(n,r){return isAdNode(n)?n:origInsert.call(this,n,r);};"
            + "document.addEventListener('click',function(e){"
            + "var a=e.target&&e.target.closest?e.target.closest('a'):null;"
            + "if(!a)return;"
            + "var href=(a.getAttribute('href')||'').toLowerCase();"
            + "if(a.target==='_blank'){a.removeAttribute('target');}"
            + "for(var i=0;i<adKw.length;i++){if(href.indexOf(adKw[i])!==-1){e.preventDefault();e.stopPropagation();return;}}"
            + "},true);"
            + "}"
            + "}catch(e){}"
            + "try{"
            + "if(!document.getElementById('gt_ad_hide')){"
            + "var s=document.createElement('style');s.id='gt_ad_hide';"
            + "s.textContent=\"ins.adsbygoogle,[id^='div-gpt-ad'],[id*='ad-'],[class*='ad-banner'],"
            + "[class*='ad-slot'],[class*='ad-box'],[class*='popunder'],[class*='sticky-ad'],"
            + "[class*='floating-ad'],[id*='exo_'],[id*='ts_ad'],"
            + "iframe[src*='ads'],iframe[src*='exoclick'],iframe[src*='juicyads'],iframe[src*='trafficjunky'],"
            + "iframe[src*='popads'],iframe[src*='adsterra'],iframe[src*='plugrush'],iframe[src*='magsrv'],"
            + "iframe[src*='realsrv'],iframe[src*='tsyndicate'],iframe[src*='chaturbate'],"
            + "iframe[src*='stripchat'],iframe[src*='livejasmin'],iframe[src*='bongacams']"
            + "{display:none!important;visibility:hidden!important;height:0!important;pointer-events:none!important;}\";"
            + "(document.head||document.documentElement).appendChild(s);"
            + "}"
            + "}catch(e){}"
            + "function sweep(){"
            + "try{"
            + "var links=document.querySelectorAll('a[target=\"_blank\"]');"
            + "for(var j=0;j<links.length;j++){links[j].removeAttribute('target');}"
            + "var vw=window.innerWidth||0,vh=window.innerHeight||0;"
            + "if(vw>0&&vh>0){"
            + "var els=document.querySelectorAll('div,a,span,section');"
            + "for(var i=0;i<els.length;i++){"
            + "var el=els[i];"
            + "if(el.tagName==='VIDEO'||el.closest('video,.plyr,.vjs-tech,.jwplayer'))continue;"
            + "if(el.querySelector&&el.querySelector('video,.plyr,.vjs-tech,.jwplayer'))continue;"
            + "var cs=window.getComputedStyle(el);"
            + "if((cs.position==='fixed'||cs.position==='absolute')&&parseInt(cs.zIndex,10)>10){"
            + "var r=el.getBoundingClientRect();"
            + "if(r.width>=vw*0.7&&r.height>=vh*0.7){"
            + "var op=parseFloat(cs.opacity);"
            + "var empty=!(el.innerText&&el.innerText.trim().length>0);"
            + "if((!isNaN(op)&&op<0.15)||empty){"
            + "el.style.pointerEvents='none';el.style.display='none';"
            + "}"
            + "}"
            + "}"
            + "}"
            + "}"
            + "}catch(e){}"
            + "}"
            + "sweep();"
            + "try{"
            + "if(!window.__gtObserver&&document.documentElement){"
            + "var rafPending=false;"
            + "window.__gtObserver=new MutationObserver(function(){"
            + "if(rafPending)return;"
            + "rafPending=true;"
            + "requestAnimationFrame(function(){rafPending=false;sweep();});"
            + "});"
            + "window.__gtObserver.observe(document.documentElement,{childList:true,subtree:true});"
            + "}"
            + "}catch(e){}"
            + "})();";

    private WebView mWebView;
    private FrameLayout mFullscreenContainer;
    private PortalAdBlocker mAdBlocker;
    @Nullable private String mCurrentRootDomain;
    @Nullable private View mCustomView;
    @Nullable private WebChromeClient.CustomViewCallback mCustomViewCallback;

    public static void launch(@NonNull Context context, @NonNull String url, @Nullable String title) {
        Intent intent = new Intent(context, MobileWebPortalActivity.class);
        intent.putExtra(EXTRA_URL, url);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mobile_web_portal);

        mAdBlocker = PortalAdBlocker.getInstance(this);
        mWebView = findViewById(R.id.web_portal_webview);
        mFullscreenContainer = findViewById(R.id.web_portal_fullscreen_container);

        ViewCompat.setOnApplyWindowInsetsListener(mWebView, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        configureWebView();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mCustomView != null) {
                    exitCustomView();
                } else if (mWebView.canGoBack()) {
                    mWebView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        String url = getIntent().getStringExtra(EXTRA_URL);
        if (!TextUtils.isEmpty(url)) {
            mCurrentRootDomain = extractRootDomain(Uri.parse(url).getHost());
            mWebView.loadUrl(url);
        } else {
            finish();
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        mWebView.setBackgroundColor(ContextCompat.getColor(this, R.color.mobile_color_background));
        mWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setUserAgentString(CHROME_MOBILE_UA);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(mWebView, true);

        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                return false;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (mCustomView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                mCustomView = view;
                mCustomViewCallback = callback;
                mFullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                mFullscreenContainer.setVisibility(View.VISIBLE);
            }

            @Override
            public void onHideCustomView() {
                exitCustomView();
            }
        });

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request != null ? request.getUrl() : null;
                if (uri == null) return true;
                String scheme = uri.getScheme();
                if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                    return true;
                }
                if (mAdBlocker.isBlockedRequest(uri)) {
                    return true;
                }
                String host = uri.getHost();
                String targetRoot = extractRootDomain(host);
                boolean crossDomain = !TextUtils.isEmpty(mCurrentRootDomain)
                        && !TextUtils.isEmpty(targetRoot)
                        && !mCurrentRootDomain.equals(targetRoot);
                if (request.isRedirect() && !request.hasGesture() && crossDomain) {
                    return true;
                }
                if (crossDomain && isRedirectTrackerUrl(uri)) {
                    return true;
                }
                if (request.isForMainFrame() && !TextUtils.isEmpty(targetRoot)) {
                    mCurrentRootDomain = targetRoot;
                }
                return false;
            }

            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request != null ? request.getUrl() : null;
                if (uri != null && mAdBlocker.isBlockedRequest(uri)) {
                    return PortalAdBlocker.emptyResponse();
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                String root = extractRootDomain(Uri.parse(url).getHost());
                if (!TextUtils.isEmpty(root)) {
                    mCurrentRootDomain = root;
                }
                view.evaluateJavascript(SHIELD_JS, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(SHIELD_JS, null);
            }
        });
    }

    private static boolean isRedirectTrackerUrl(@NonNull Uri uri) {
        String lower = uri.toString().toLowerCase(Locale.US);
        for (String pattern : REDIRECT_TRACKER_PATTERNS) {
            if (lower.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static String extractRootDomain(@Nullable String rawHost) {
        if (TextUtils.isEmpty(rawHost)) {
            return null;
        }
        String host = rawHost.toLowerCase(Locale.US);
        String[] parts = host.split("\\.");
        if (parts.length < 2) {
            return host;
        }
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    private void exitCustomView() {
        if (mCustomView == null) {
            return;
        }
        mFullscreenContainer.setVisibility(View.GONE);
        mFullscreenContainer.removeView(mCustomView);
        mCustomView = null;
        if (mCustomViewCallback != null) {
            mCustomViewCallback.onCustomViewHidden();
            mCustomViewCallback = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mWebView != null) {
            mWebView.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (mWebView != null) {
            mWebView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        exitCustomView();
        if (mWebView != null) {
            ViewGroup parent = (ViewGroup) mWebView.getParent();
            if (parent != null) {
                parent.removeView(mWebView);
            }
            mWebView.stopLoading();
            mWebView.destroy();
            mWebView = null;
        }
        super.onDestroy();
    }
}
