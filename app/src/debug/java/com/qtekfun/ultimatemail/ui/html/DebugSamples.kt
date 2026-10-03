// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.html

/**
 * Sample messages for the debug screen. The hostile one writes "SCRIPT RAN" over the page if
 * anything executes, and every remote reference points at a host that does not exist, so a
 * request that leaks out can only fail, never reach anyone.
 */
internal object DebugSamples {
    const val HOSTILE = """
        <h3>Hostile sample</h3>
        <p>If you can read this, and no line below says "SCRIPT RAN", scripts did not run.</p>
        <script>document.body.innerHTML = '<h1>SCRIPT RAN</h1>';</script>
        <img src="https://tracker.invalid/pixel.gif?id=1" width="1" height="1" alt="">
        <img src="x" onerror="document.body.innerHTML = '<h1>SCRIPT RAN</h1>'">
        <p style="background:url(https://tracker.invalid/bg.png);width:expression(alert(1))">Styled.</p>
        <a href="javascript:document.body.innerHTML='SCRIPT RAN'">javascript: link</a>
        <a href="https://evil.invalid/login">https://bank.example.com/login</a>
        <iframe src="https://evil.invalid/frame"></iframe>
        <svg onload="document.body.innerHTML = 'SCRIPT RAN'"></svg>
        <form action="https://evil.invalid/post"><input name="pw" type="password"></form>
        <meta http-equiv="refresh" content="0;url=https://evil.invalid/redirect">
        <base href="https://evil.invalid/">
    """

    const val NEWSLETTER = """
        <style>
          .btn { background-color: #0a66c2; color: #ffffff; padding: 12px 24px; }
          @media (max-width: 600px) { .box { width: 100% !important; } }
        </style>
        <table class="box" width="600" cellpadding="8" bgcolor="#eeeeee" align="center">
          <tr><td style="font-family:Arial,sans-serif;color:#333333">
            <h2>Monthly newsletter</h2>
            <img src="https://cdn.invalid/logo.png" alt="Remote logo" width="120" height="40">
            <p>Plain text with <b>bold</b>, <i>italics</i> and an
               <a href="https://example.com/article?id=1&amp;utm=mail">honest link</a>.</p>
            <a class="btn" href="mailto:hello@example.com">Write to us</a>
          </td></tr>
        </table>
        <img src="https://tracker.invalid/open.gif" width="1" height="1" alt="">
    """

    const val LINKS = """
        <p><a href="https://example.com/">https://example.com/</a> (same address)</p>
        <p><a href="https://evil.invalid/x">https://example.com/</a> (different address)</p>
        <p><a href="mailto:someone@example.com">someone@example.com</a></p>
        <p><a href="tel:+34600000000">+34 600 000 000</a></p>
        <p><a href="data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==">data: link (dropped)</a></p>
    """
}
