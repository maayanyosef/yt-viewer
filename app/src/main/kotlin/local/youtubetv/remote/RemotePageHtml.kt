package local.youtubetv.remote

import local.youtubetv.APP_NAME
import local.youtubetv.apikey.escapeHtml
import java.security.MessageDigest
import java.util.Base64

/**
 * The phone remote's page. One HTML page, one script, no library.
 *
 * The script asks the television for `state` every two seconds and for a Source's `videos`
 * when one is chosen, and sends every tap as a `POST cmd`. The pictures are YouTube's own
 * thumbnails, fetched by the phone straight from `i.ytimg.com`: the phone has the internet and
 * the television does not have the memory to serve pictures.
 *
 * Titles are drawn with `dir="auto"`, so a Hebrew title reads right to left and an English one
 * left to right, each on its own.
 *
 * @param basePath the token's path, which every request of the page is under.
 */
fun remotePageHtml(basePath: String): String = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="referrer" content="no-referrer">
<meta name="theme-color" content="#111">
<title>$APP_NAME remote</title>
<style>
  :root { color-scheme: dark; --bg:#111; --card:#1d1d1f; --ink:#f2f2f2; --muted:#9a9a9a; --accent:#3d8bff; --green:#3ccb5a; }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  body { margin: 0; background: var(--bg); color: var(--ink); font: 16px/1.4 system-ui, sans-serif; padding-bottom: 24px; }
  header { position: sticky; top: 0; background: var(--bg); padding: 12px 16px 8px; z-index: 2; border-bottom: 1px solid #222; }
  h1 { font-size: 15px; margin: 0 0 8px; color: var(--muted); font-weight: 600; letter-spacing: .02em; }
  .now { display: flex; gap: 12px; align-items: center; }
  .now img { width: 112px; height: 63px; object-fit: cover; border-radius: 8px; background: #000; flex: none; }
  .now .t { font-weight: 600; font-size: 15px; overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
  .now .p { color: var(--muted); font-size: 13px; margin-top: 2px; }
  .bar { height: 3px; background: #333; border-radius: 2px; margin-top: 8px; overflow: hidden; }
  .bar i { display: block; height: 100%; background: var(--accent); width: 0; }
  .keys { display: grid; grid-template-columns: repeat(5, 1fr); gap: 8px; margin-top: 12px; }
  button { font: inherit; color: var(--ink); background: var(--card); border: 0; border-radius: 12px; padding: 14px 0; font-size: 18px; font-weight: 600; }
  button:active { background: #2c2c30; }
  button.big { font-size: 26px; padding: 10px 0; }
  button[disabled] { opacity: .35; }
  .row { display: flex; gap: 8px; margin-top: 10px; flex-wrap: wrap; }
  .row button { flex: 1; padding: 10px 8px; font-size: 14px; }
  .row button.on { background: var(--accent); color: #fff; }
  section { padding: 12px 16px 0; }
  h2 { font-size: 13px; color: var(--muted); margin: 10px 0 6px; font-weight: 600; text-transform: uppercase; letter-spacing: .06em; }
  .chips { display: flex; gap: 8px; overflow-x: auto; padding-bottom: 6px; }
  .chips button { flex: none; font-size: 14px; padding: 10px 14px; white-space: nowrap; }
  .chips button.on { background: var(--accent); color: #fff; }
  input[type=search] { width: 100%; font: inherit; padding: 12px; border-radius: 12px; border: 0; background: var(--card); color: var(--ink); margin: 6px 0 8px; }
  .videos { display: grid; gap: 10px; }
  .v { display: flex; gap: 10px; background: var(--card); border-radius: 12px; padding: 8px; text-align: left; align-items: center; font-weight: 500; font-size: 14px; }
  .v img { width: 120px; height: 68px; object-fit: cover; border-radius: 8px; background: #000; flex: none; }
  .v .t { overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
  .v .m { color: var(--muted); font-size: 12px; margin-top: 3px; }
  .v.watched { opacity: .55; }
  .v .tick { color: var(--green); }
  .more { margin-top: 10px; width: 100%; font-size: 14px; }
  .note { color: var(--muted); font-size: 13px; padding: 10px 0; }
  .off { color: #ff7b7b; }
</style>
</head>
<body data-base="${escapeHtml(basePath)}">
<header>
  <h1>$APP_NAME remote <span id="status" class="note"></span></h1>
  <div class="now" id="now" hidden>
    <img id="nowImg" alt="">
    <div><div class="t" id="nowTitle" dir="auto"></div><div class="p" id="nowPos"></div></div>
  </div>
  <div class="bar" id="bar" hidden><i id="barFill"></i></div>
  <div class="keys">
    <button class="big" data-cmd="action=prev" title="Previous video">&#9198;</button>
    <button class="big" data-cmd="action=seek&amp;by=-10">-10</button>
    <button class="big" id="toggle" data-cmd="action=toggle">&#9654;</button>
    <button class="big" data-cmd="action=seek&amp;by=10">+10</button>
    <button class="big" data-cmd="action=next" title="Next video">&#9197;</button>
  </div>
  <div class="row">
    <button data-cmd="action=back">Back</button>
    <button data-sleep="after">Stop after this video</button>
    <button data-sleep="15">15 min</button>
    <button data-sleep="30">30 min</button>
    <button data-sleep="60">60 min</button>
    <button data-sleep="off" id="sleepOff" hidden>Timer off</button>
    <button id="child">Child mode</button>
  </div>
  <div class="note" id="sleepNote"></div>
</header>
<section>
  <h2>Channels and playlists</h2>
  <div class="chips" id="sources"></div>
  <input type="search" id="search" placeholder="Search in this Source" autocomplete="off">
  <div class="row" id="sorts">
    <button data-sort="date" class="on">By date</button>
    <button data-sort="views">Most viewed</button>
    <button data-sort="duration">Longest</button>
  </div>
  <div class="videos" id="videos"></div>
  <button class="more" id="more" hidden>Show more</button>
  <div class="note" id="empty" hidden>No videos match.</div>
</section>
<script>$REMOTE_PAGE_SCRIPT</script>
</body>
</html>
""".trimIndent()

/**
 * The page's script. Allowed by its SHA-256 in [REMOTE_CONTENT_SECURITY_POLICY], and holding no
 * Kotlin value, so a title with a quote in it cannot break it and the hash never changes with
 * the wording.
 */
internal val REMOTE_PAGE_SCRIPT = """
(function () {
  var base = document.body.dataset.base;
  var state = null;
  var source = null;
  var offset = 0;
  var query = "";
  var sort = "date";
  var el = function (id) { return document.getElementById(id); };
  var status = el("status");

  function clock(s) {
    if (s == null) return "";
    s = Math.max(0, Math.floor(s));
    var h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = s % 60;
    var mm = (h ? String(m).padStart(2, "0") : m) + ":" + String(x).padStart(2, "0");
    return h ? h + ":" + mm : mm;
  }

  function send(body) {
    return fetch(base + "/cmd", { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: body })
      .then(function () { setTimeout(poll, 300); })
      .catch(function () { status.textContent = "no connection"; status.className = "note off"; });
  }

  document.querySelectorAll("[data-cmd]").forEach(function (b) {
    b.addEventListener("click", function () { send(b.dataset.cmd); });
  });
  el("child").addEventListener("click", function () {
    send("action=child_mode&on=" + (state && state.childMode ? "0" : "1"));
  });
  document.querySelectorAll("[data-sleep]").forEach(function (b) {
    b.addEventListener("click", function () {
      var v = b.dataset.sleep;
      if (v === "off") return send("action=sleep");
      if (v === "after") return send("action=sleep&after_video=1");
      send("action=sleep&minutes=" + v);
    });
  });

  function drawState(s) {
    state = s;
    status.textContent = "";
    status.className = "note";
    var now = s.nowPlaying;
    el("now").hidden = !now;
    el("bar").hidden = !(now && now.duration);
    if (now) {
      el("nowImg").src = "https://i.ytimg.com/vi/" + encodeURIComponent(now.videoId) + "/mqdefault.jpg";
      el("nowTitle").textContent = now.title;
      el("nowPos").textContent = (now.playing ? "" : "Paused  ") + clock(now.position) + (now.duration ? " / " + clock(now.duration) : "");
      el("toggle").innerHTML = now.playing ? "&#10074;&#10074;" : "&#9654;";
      if (now.duration) el("barFill").style.width = Math.min(100, 100 * now.position / now.duration) + "%";
    } else {
      el("toggle").innerHTML = "&#9654;";
    }
    el("child").textContent = s.childMode ? "Child mode: on" : "Child mode: off";
    el("child").classList.toggle("on", !!s.childMode);
    var sleep = s.sleep;
    var parts = [];
    if (sleep.afterVideo) parts.push("stops after this video");
    if (sleep.secondsLeft != null) parts.push("stops in " + clock(sleep.secondsLeft));
    el("sleepNote").textContent = parts.length ? "Sleep timer: " + parts.join(", ") : "";
    el("sleepOff").hidden = parts.length === 0;
    document.querySelectorAll("[data-sleep]").forEach(function (b) {
      var v = b.dataset.sleep;
      b.classList.toggle("on", (v === "after" && sleep.afterVideo) || (v !== "after" && v !== "off" && sleep.secondsLeft != null && Math.abs(sleep.secondsLeft - v * 60) < 90));
    });
    var chips = el("sources");
    var html = "";
    s.sources.forEach(function (src) {
      html += '<button data-source="' + src.id + '" class="' + (src.id === source ? "on" : "") + '"><span dir="auto">' + text(src.name) + "</span> (" + src.count + ")</button>";
    });
    chips.innerHTML = html;
    chips.querySelectorAll("[data-source]").forEach(function (b) {
      b.addEventListener("click", function () { chooseSource(b.dataset.source, true); });
    });
    if (source === null) {
      var open = s.openSource || (s.sources[0] && s.sources[0].id);
      if (open) chooseSource(open, false);
    }
  }

  function text(t) { var d = document.createElement("div"); d.textContent = t; return d.innerHTML; }

  function chooseSource(id, fromTap) {
    source = id;
    offset = 0;
    el("videos").innerHTML = "";
    el("search").value = "";
    query = "";
    if (state) drawState(state);
    loadVideos();
  }

  function loadVideos() {
    if (!source) return;
    fetch(base + "/videos?source=" + encodeURIComponent(source) + "&offset=" + offset + "&sort=" + sort + "&q=" + encodeURIComponent(query))
      .then(function (r) { return r.json(); })
      .then(function (page) {
        var list = el("videos");
        if (offset === 0) list.innerHTML = "";
        page.videos.forEach(function (v) {
          var b = document.createElement("button");
          b.className = "v" + (v.watched ? " watched" : "");
          var when = v.published ? new Date(v.published).toLocaleDateString() : "";
          if (v.views != null) when += " · " + (v.views >= 1000000 ? (v.views / 1000000).toFixed(1) + "M" : v.views >= 1000 ? Math.round(v.views / 1000) + "K" : v.views) + " views";
          var pos = v.position && !v.watched ? " · at " + clock(v.position) : "";
          b.innerHTML = '<img loading="lazy" alt="" src="https://i.ytimg.com/vi/' + encodeURIComponent(v.id) + '/mqdefault.jpg">' +
            '<div><div class="t" dir="auto">' + text(v.title) + '</div><div class="m">' +
            (v.watched ? '<span class="tick">&#10003; watched</span> · ' : "") + when + (v.duration ? " · " + clock(v.duration) : "") + pos + "</div></div>";
          b.addEventListener("click", function () {
            send("action=play_video&source=" + encodeURIComponent(source) + "&video=" + encodeURIComponent(v.id));
            window.scrollTo({ top: 0, behavior: "smooth" });
          });
          list.appendChild(b);
        });
        offset += page.videos.length;
        el("more").hidden = offset >= page.total;
        el("empty").hidden = page.total > 0;
      })
      .catch(function () {});
  }

  el("more").addEventListener("click", loadVideos);
  document.querySelectorAll("[data-sort]").forEach(function (b) {
    b.addEventListener("click", function () {
      sort = b.dataset.sort;
      document.querySelectorAll("[data-sort]").forEach(function (x) { x.classList.toggle("on", x === b); });
      offset = 0;
      loadVideos();
    });
  });
  var typing = null;
  el("search").addEventListener("input", function (e) {
    clearTimeout(typing);
    typing = setTimeout(function () { query = e.target.value; offset = 0; loadVideos(); }, 250);
  });

  function poll() {
    fetch(base + "/state").then(function (r) { return r.json(); }).then(drawState)
      .catch(function () { status.textContent = "television not reachable"; status.className = "note off"; });
  }
  poll();
  setInterval(poll, 2000);
})();
""".trim()

internal val REMOTE_PAGE_SCRIPT_HASH: String =
    "'sha256-" + Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(REMOTE_PAGE_SCRIPT.toByteArray(Charsets.UTF_8)),
    ) + "'"

/**
 * The remote page's Content-Security-Policy: its own inline style, its one script by hash,
 * YouTube's thumbnail host for pictures, and the television itself for `state`, `videos` and
 * `cmd`. Nothing else.
 */
internal val REMOTE_CONTENT_SECURITY_POLICY: String =
    "default-src 'none'; style-src 'unsafe-inline'; script-src $REMOTE_PAGE_SCRIPT_HASH; " +
        "img-src https://i.ytimg.com; connect-src 'self'; frame-ancestors 'none'"
