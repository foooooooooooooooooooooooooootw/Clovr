# Adding New Sites & Captcha Guide

## How the site system works

Every supported site is a class that implements the `Site` interface (usually by
extending `SiteBase` or the higher-level `CommonSite`). The app discovers sites
via `SiteRegistry`, which is a static map of integer IDs to site classes.

There are two tiers of site implementation:

### Tier 1 — Full custom implementation (like Chan4)
Use this when the site has a unique API, authentication, or posting format.
You extend `SiteBase` directly and wire everything up yourself.

### Tier 2 — Vichan/LynxChan board (like Lainchan, Sushichan, Arisuchan)
Most alternative imageboards run **vichan**, **NPFchan**, **LynxChan**, or
**OpenIB** — all of which share roughly the same JSON API and posting format
as classic 4chan. The codebase already has a full `vichan/` layer you can
reuse with almost no code. This covers the vast majority of altchans.

---

## Adding a vichan-based site (the easy case)

This is about 50 lines of code. Copy `Lainchan.java` as your starting point.

### 1. Create the site class

Create `app/src/main/java/org/floens/chan/core/site/sites/YOURSITE/YourSite.java`:

```java
package org.floens.chan.core.site.sites.yoursite;

import androidx.annotation.Nullable;
import org.floens.chan.core.model.Post;
import org.floens.chan.core.model.orm.Board;
import org.floens.chan.core.model.orm.Loadable;
import org.floens.chan.core.site.Site;
import org.floens.chan.core.site.SiteIcon;
import org.floens.chan.core.site.common.CommonSite;
import org.floens.chan.core.site.common.vichan.VichanActions;
import org.floens.chan.core.site.common.vichan.VichanApi;
import org.floens.chan.core.site.common.vichan.VichanCommentParser;
import org.floens.chan.core.site.common.vichan.VichanEndpoints;
import okhttp3.HttpUrl;

public class YourSite extends CommonSite {
    public static final CommonSiteUrlHandler URL_HANDLER = new CommonSiteUrlHandler() {
        @Override
        public Class<? extends Site> getSiteClass() { return YourSite.class; }

        @Override
        public HttpUrl getUrl() { return HttpUrl.parse("https://example.org/"); }

        @Override
        public String[] getNames() { return new String[]{"yoursite"}; }

        @Override
        public String desktopUrl(Loadable loadable, @Nullable Post post) {
            if (loadable.isCatalogMode()) {
                return getUrl().newBuilder().addPathSegment(loadable.boardCode).toString();
            } else if (loadable.isThreadMode()) {
                return getUrl().newBuilder()
                        .addPathSegment(loadable.boardCode)
                        .addPathSegment("res")
                        .addPathSegment(loadable.no + ".html")
                        .toString();
            }
            return getUrl().toString();
        }
    };

    @Override
    public void setup() {
        setName("YourSite");
        // Load the favicon — the app will cache it automatically
        setIcon(SiteIcon.fromFavicon(HttpUrl.parse("https://example.org/favicon.ico")));

        // List boards statically (STATIC type) — or use INFINITE if boards are user-entered
        setBoards(
                Board.fromSiteNameCode(this, "General", "b"),
                Board.fromSiteNameCode(this, "Technology", "tech")
                // ... add more
        );

        setResolvable(URL_HANDLER);

        setConfig(new CommonConfig() {
            @Override
            public boolean feature(Feature feature) {
                // Enable posting support — remove if the site is read-only
                return feature == Feature.POSTING;
            }
        });

        // Both URL args: first is the media/image CDN, second is the posting endpoint root
        // For most vichan sites they're the same domain
        setEndpoints(new VichanEndpoints(this,
                "https://example.org",
                "https://example.org"));
        setActions(new VichanActions(this));  // handles post/delete HTTP calls
        setApi(new VichanApi(this));          // parses catalog.json / thread JSON
        setParser(new VichanCommentParser()); // parses >>quotes and markup
    }
}
```

### 2. Register the site

In `SiteRegistry.java`, add two lines:

```java
// In the URL_HANDLERS list:
URL_HANDLERS.add(YourSite.URL_HANDLER);

// In the SITE_CLASSES map — use the next available integer:
SITE_CLASSES.put(6, YourSite.class);  // increment from the last entry
```

**Important:** never change the integer ID of an existing site. These IDs are
persisted in the user's database to remember which sites they've added. Changing
them will corrupt existing installs.

### 3. Add the icon asset (optional but nice)

Drop a small PNG (32×32 or 64×64) into
`app/src/main/assets/icons/yoursite.png` and use
`SiteIcon.fromAssets("icons/yoursite.png")` instead of `fromFavicon(...)` for
offline loading.

---

## Sites with different JSON formats (LynxChan, etc.)

LynxChan (used by sites like 16chan, endchan) has a different JSON structure than
vichan. You'll need to write a custom `ChanReader` (like `FutabaChanReader`).
Look at `VichanApi.java` to understand the pattern — it extends
`CommonSite.CommonApi` and overrides `readThreadObject` / `readPostObject`.

The key fields to map are:
- Thread/post number → `builder.id(...)`  
- Subject → `builder.subject(...)`  
- Name/trip → `builder.name(...)` / `builder.tripcode(...)`  
- Comment HTML → `builder.comment(...)`  
- File info → construct a `PostImage` via `PostImage.Builder`  
- OP detection → `builder.op(resto == 0)`

---

## Captcha situation — what's broken and why

### The noscript endpoint is dead

The old "noscript" captcha path (`CAPTCHA2_NOJS` / `CaptchaNoJsPresenterV2`)
worked by scraping Google's `/recaptcha/api/fallback` endpoint — it fetched
image tiles, let you click them, and submitted the form. **Google shut this
endpoint down** (it now just redirects). This is why posting is broken for
anyone who didn't have a 4chan Pass.

### The three captcha options now

| Option | How it works | Reliability |
|--------|-------------|-------------|
| `V2JS` | Embeds `captcha2.html` in a `WebView`, loads `api.js` from Google, renders the full reCAPTCHA widget | Works as long as Google doesn't change the widget API |
| `V2NOJS` | ~~Scrapes Google's fallback endpoint~~ | **Broken** — endpoint is dead |
| `V2_WEBVIEW` *(new)* | Opens the actual 4chan board page in a full WebView, user solves the captcha natively, app watches for success | Most resilient — survives captcha format changes without app update |

**Recommendation:** Default new installs to `V2_WEBVIEW`. The `V2JS` widget
approach also still works and is less intrusive (just shows the widget, not the
whole page), but if Google or 4chan changes anything it requires an app update.
`V2_WEBVIEW` degrades gracefully because it's literally just the browser.

### 4chan specifically: hCaptcha and t-captcha

4chan has at various times used: reCAPTCHA v1 (dead), reCAPTCHA v2 (current),
and their own "t-captcha" (a simplified image challenge served from
`sys.4chan.org`). The `V2_WEBVIEW` approach handles all of these automatically.

### For other sites

Most vichan sites either have no captcha or use a simple text/math captcha.
`VichanActions.postAuthenticate()` returns `SiteAuthentication.fromNone()` by
default — correct for most altchans. If a site adds captcha, override
`postAuthenticate()` in your site's actions class:

```java
@Override
public SiteAuthentication postAuthenticate() {
    // For a site using its own reCAPTCHA key:
    return SiteAuthentication.fromCaptcha2("YOUR_SITE_KEY_HERE", "https://example.org");
    
    // Or for maximum resilience — just open the post form in a WebView:
    // return SiteAuthentication.fromUrl(
    //     "https://example.org/b/",   // URL to load
    //     "Solve the captcha",         // hint shown to user
    //     "captcha_solved");           // text to watch for in page source
}
```

---

## Quick checklist for a new vichan site

- [ ] Create `YourSite.java` in its own package under `sites/`
- [ ] Set correct base URL in `getUrl()`
- [ ] List boards (or use `BoardsType.INFINITE` + let users enter board codes)
- [ ] Set both CDN and posting URL in `VichanEndpoints` constructor
- [ ] Add to `SiteRegistry` with a new unique integer ID
- [ ] Test: browse catalog, open thread, attempt to post
- [ ] If posting is broken: check `VichanActions.handlePost()` error regex against the site's actual error HTML
