package local.youtubetv.apikey

import local.youtubetv.APP_NAME
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

/**
 * The Key page: the guide to creating a key, and a field to send it. Build ticket 64.
 *
 * **Plain HTML, with one small script that only adds buttons.** The form posts back to the same
 * address, and the answer is this page again with the result at the top. It works on every phone
 * and computer browser. The script is [KEY_PAGE_SCRIPT]. It shows the Copy button and, where the
 * browser has it, a Share button. Without the script the page loses only those two buttons. The
 * Content-Security-Policy allows that one script by its hash and no other. Build ticket 66.
 *
 * Every console address and button name below was checked against Google's own documentation
 * on 2026-10-17, not written from memory:
 *
 * - `docs.cloud.google.com/docs/authentication/api-keys`: `Create credentials`, `API key`,
 *   `Restrict key`, `API restrictions`, `Save`, and the Credentials page address.
 * - `developers.google.com/workspace/guides/create-project`: the `projectcreate` address,
 *   `Project Name` and `Create`.
 * - `developers.google.com/workspace/guides/enable-apis`: `Enable` in the API Library.
 * - `developers.google.com/youtube/v3/getting-started`: the quota of 10,000 units a day.
 *
 * @param result the answer to the last key sent, or null before anything was sent.
 * @param postPath the page's own path, which is where the form posts.
 * @param address the page's full address, exactly as the television prints it, or null when the
 *   television has none to print. It is built on the television from the same value as the
 *   screen's text and QR code, never from what the browser asked for. Build ticket 66.
 */
fun keyPageHtml(result: KeyCheckResult?, postPath: String, address: String?): String {
    val banner = result?.let { done ->
        val kind = if (done.saves) "ok" else "bad"
        """<p class="result $kind">${escapeHtml(keyCheckMessage(done))}</p>"""
    }.orEmpty()

    // Once a key is saved the television leaves the key screen and this page stops answering,
    // so there is nothing more to send from it.
    val form = if (result?.saves == true) {
        "<p>You can close this page. The television has moved on to its home screen.</p>"
    } else {
        """
        <form method="post" action="${escapeHtml(postPath)}">
          <label for="key">Your API key</label>
          <input id="key" name="key" type="text" autocomplete="off" autocapitalize="off"
                 autocorrect="off" spellcheck="false" placeholder="AIza…" required>
          <button type="submit">Send to the television</button>
        </form>
        """.trimIndent()
    }

    // Once a key is saved, or the key screen has closed, the address no longer answers, so it is
    // not offered for sharing. `SCREEN_CLOSED` found by `/code-review` on build ticket 66.
    val screenGone = result?.saves == true || result == KeyCheckResult.SCREEN_CLOSED
    val addressBlock = address?.takeUnless { screenGone }?.let(::keyPageAddressBlock).orEmpty()
    val script = if (addressBlock.isEmpty()) "" else "<script>$KEY_PAGE_SCRIPT</script>"

    return """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>$APP_NAME: your API key</title>
<style>
  :root { color-scheme: light dark; }
  body { font: 17px/1.5 system-ui, sans-serif; margin: 0 auto; max-width: 640px; padding: 16px; }
  h1 { font-size: 22px; } h2 { font-size: 18px; margin-top: 28px; }
  ol li { margin-bottom: 12px; }
  b { white-space: nowrap; }
  .result { padding: 12px; border-radius: 8px; font-weight: 600; }
  .ok { background: #d9f2dd; color: #10421b; } .bad { background: #fbdcdc; color: #5b1010; }
  label { display: block; font-weight: 600; margin-bottom: 6px; }
  input { width: 100%; box-sizing: border-box; font: 16px monospace; padding: 12px; }
  button { margin-top: 12px; width: 100%; font: 600 17px system-ui, sans-serif; padding: 12px; }
  .address { display: block; width: 100%; box-sizing: border-box; font: 600 18px monospace;
             padding: 12px; resize: none; overflow-wrap: anywhere; }
  a.button { display: block; margin-top: 12px; padding: 12px; text-align: center;
             font-weight: 600; border: 1px solid currentColor; border-radius: 8px; }
  .warning { padding: 12px; border-radius: 8px; background: #fff3cd; color: #4a3800; }
</style>
</head>
<body>
<h1>Connect your YouTube API key</h1>
$banner
$form
<h2>How to create a key</h2>
<p>It is free and needs no billing. Use any Google account. It takes about five minutes.
A computer is easier than a phone for these steps. You can open this page on either.</p>
$addressBlock
<ol>
  <li>Open <a href="https://console.cloud.google.com/projectcreate" target="_blank">the
    Create a Project page</a>. Sign in, and accept the terms if Google asks. In
    <b>Project Name</b>, type a name such as <i>$APP_NAME</i>, and click <b>Create</b>.
    Wait until the project exists, and check that it is the project selected at the top of
    the page.</li>
  <li>Open <a href="https://console.cloud.google.com/apis/library/youtube.googleapis.com"
    target="_blank">YouTube Data API v3 in the API Library</a> and click <b>Enable</b>.
    If the link shows something else, open the API Library, search for
    <i>YouTube Data API v3</i>, and enable it there.</li>
  <li>Open <a href="https://console.cloud.google.com/apis/credentials" target="_blank">the
    Credentials page</a>. Click <b>Create credentials</b>, then <b>API key</b>.</li>
  <li>Restrict the key to YouTube. If the form shows <b>API restrictions</b>, choose
    <b>Restrict key</b>, select <i>YouTube Data API v3</i>, and click <b>Create</b>.
    If the key was created at once, click its name on the Credentials page, choose
    <b>Restrict key</b> under <b>API restrictions</b>, select <i>YouTube Data API v3</i>, and
    click <b>Save</b>. Leave application restrictions at <i>None</i>.</li>
  <li>Copy the key. It is a long string that starts with <i>AIza</i>. Paste it in the field
    above and send it.</li>
</ol>
<h2>What the key allows</h2>
<p>Your key gets 10,000 units a day from YouTube for free. This app uses a small part of that.
If a day's units run out, the app keeps working from what it already has, and updates again
after midnight Pacific Time.</p>
<p>The key is saved on the television only. It is sent over your home network and nowhere
else.</p>
$script
</body>
</html>
""".trimStart()
}

/** The subject line of the email the page offers to send. */
internal const val KEY_PAGE_EMAIL_SUBJECT = "$APP_NAME: the page for your API key"

/**
 * The text the email and the WhatsApp message carry: one sentence, then the address.
 *
 * The sentence repeats the network rule, because the message is read on another device, away
 * from the warning on the page.
 */
fun keyPageShareText(address: String): String =
    "Open this page on a computer connected to the same home network as the television: $address"

/**
 * Percent-encodes [text] for a link. Every space is `%20`, never `+`.
 *
 * WhatsApp's help page "How to use click to chat" (`faq.whatsapp.com/5913398998672934`) asks for
 * `https://wa.me/?text=urlencodedtext` and shows a space as `%20`. RFC 6068 says the same for a
 * `mailto:` body, where a `+` stays a plus sign. Checked on 2026-10-17.
 */
fun encodeForLink(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

/** The `mailto:` link. It has no recipient, so the reader picks one, often their own address. */
fun keyPageMailtoLink(address: String): String =
    "mailto:?subject=${encodeForLink(KEY_PAGE_EMAIL_SUBJECT)}&body=${encodeForLink(keyPageShareText(address))}"

/**
 * The WhatsApp link. With no phone number in it, WhatsApp asks whom to send it to. The format is
 * from WhatsApp's help page, see [encodeForLink].
 */
fun keyPageWhatsAppLink(address: String): String =
    "https://wa.me/?text=${encodeForLink(keyPageShareText(address))}"

/** The warning under the address. Plain words, because friends read this page too. */
internal const val KEY_PAGE_NETWORK_WARNING =
    "This address works only on a phone or computer connected to the same wifi or home network " +
        "as the television. It does not work over mobile data or from another place. It stops " +
        "working when the key screen on the television closes."

/** What the page says after Copy worked. */
internal const val KEY_PAGE_COPIED = "Copied. Paste it into an email or a message to yourself."

/** What the page says when the browser refused to copy. */
internal const val KEY_PAGE_COPY_FAILED =
    "This browser did not copy it. The address is selected, so copy it by hand."

/**
 * The page's own address, and the ways to send it to a computer. Build ticket 66.
 *
 * **The page is served over plain `http` from a private address, which is not a secure
 * context.** So `navigator.clipboard` and `navigator.share` are missing in most browsers. MDN
 * marks both "available only in secure contexts (HTTPS)". Its Secure Contexts page lists only
 * `https`, loopback and `localhost` as trustworthy. Checked on 2026-10-17. So:
 *
 * - Email and WhatsApp are plain links, which need no script at all.
 * - Copy uses `document.execCommand("copy")` on the selected field. MDN does not tie that to a
 *   secure context. It returns false when the browser refuses, and then the page says so and
 *   leaves the address selected for copying by hand.
 * - Share appears only where `navigator.share` exists, and never instead of the others.
 *
 * Both buttons start `hidden`, so a browser without script shows no button that does nothing.
 */
private fun keyPageAddressBlock(address: String): String = """
<p>The address of this page:</p>
<textarea id="address" class="address" readonly rows="2" aria-label="The address of this page">${escapeHtml(address)}</textarea>
<a class="button" href="${escapeHtml(keyPageMailtoLink(address))}">Send it by email</a>
<a class="button" href="${escapeHtml(keyPageWhatsAppLink(address))}" target="_blank" rel="noopener">Send it by WhatsApp</a>
<button id="copy" type="button" hidden>Copy the address</button>
<button id="share" type="button" hidden data-text="${escapeHtml(keyPageShareText(address))}">Share the address</button>
<p id="copied" role="status" data-done="${escapeHtml(KEY_PAGE_COPIED)}" data-failed="${escapeHtml(KEY_PAGE_COPY_FAILED)}"></p>
<p class="warning">${escapeHtml(KEY_PAGE_NETWORK_WARNING)}</p>
""".trim()

/**
 * The page's only script. It never reads the key field and sends nothing anywhere.
 *
 * [KEY_PAGE_CONTENT_SECURITY_POLICY] allows it by its SHA-256, so a change to one character of
 * it changes the hash with it. A test checks that the two agree.
 *
 * **It holds no Kotlin value.** Every sentence it shows comes from a `data-` attribute, which
 * [escapeHtml] escapes. A quote in a sentence therefore cannot break the script, and the script
 * and its hash never change with the wording. Found by `/code-review` on build ticket 66.
 */
internal val KEY_PAGE_SCRIPT = """
(function () {
  var field = document.getElementById("address");
  var copy = document.getElementById("copy");
  var share = document.getElementById("share");
  var said = document.getElementById("copied");
  if (!field || !copy || !share || !said) return;
  copy.hidden = false;
  copy.addEventListener("click", function () {
    field.focus();
    field.select();
    field.setSelectionRange(0, field.value.length);
    var done = false;
    try { done = document.execCommand("copy"); } catch (e) { done = false; }
    said.textContent = done ? said.dataset.done : said.dataset.failed;
  });
  if (typeof navigator.share === "function") {
    share.hidden = false;
    share.addEventListener("click", function () {
      navigator.share({ text: share.dataset.text }).catch(function () {});
    });
  }
})();
""".trim()

/** The `'sha256-…'` source that allows [KEY_PAGE_SCRIPT] and nothing else. */
internal val KEY_PAGE_SCRIPT_HASH: String =
    "'sha256-" + Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(KEY_PAGE_SCRIPT.toByteArray(Charsets.UTF_8)),
    ) + "'"

/**
 * The page's Content-Security-Policy. It allows only these:
 *
 * - the page's own inline style,
 * - its one script, by hash,
 * - its own form.
 *
 * Links still open, because the policy does not govern following a link.
 */
internal val KEY_PAGE_CONTENT_SECURITY_POLICY: String =
    "default-src 'none'; style-src 'unsafe-inline'; script-src $KEY_PAGE_SCRIPT_HASH; " +
        "form-action 'self'; frame-ancestors 'none'"

/** Escapes the five characters that mean something in HTML. */
fun escapeHtml(text: String): String = buildString(text.length) {
    for (char in text) {
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(char)
        }
    }
}
