// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.debug

import android.util.Base64

/** An attachment of a demo message; [bytes] is only set for one that starts out downloaded. */
class DemoAttachment(
    val name: String,
    val mimeType: String,
    val size: Long,
    val contentId: String? = null,
    val inline: Boolean = false,
    val bytes: ByteArray? = null
)

/** The body of a demo message, as the server would have delivered it. */
class DemoBody(
    val text: String? = null,
    val html: String? = null,
    val attachments: List<DemoAttachment> = emptyList()
)

/**
 * Debug builds only: bodies for the demo conversations, one of each shape the reading screen has
 * to deal with (plain text with links and a quote, HTML with a remote image and a tracking pixel,
 * a Gmail quote, an Outlook header, inline `cid:` images, attachments). The other demo messages
 * have no body, so opening one shows the "could not load" state: the demo servers do not exist.
 */
object DemoBodies {
    /** A 1x1 pixel PNG, stretched by the HTML: the "inline image" that needs no download. */
    private val pixel: ByteArray = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
        Base64.DEFAULT
    )

    private const val LONG_PARAGRAPHS = 12
    private const val LONG_HTML_BLOCKS = 400
    private const val SPREADSHEET_TYPE =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private const val LONG_NAME =
        "a-file-with-a-very-long-name-that-has-to-wrap-over-several-lines.txt"

    /** The body of the [index]th list conversation, or null to leave it without one. */
    fun forListMessage(index: Int): DemoBody? = when (index) {
        0 -> plainWithQuote()
        1 -> htmlWithRemoteImage()
        2 -> spanishReply()
        3 -> gmailQuote()
        4 -> withAttachments()
        5 -> inlineImage()
        6 -> longText()
        7 -> outlookReply()
        8 -> notification()
        9 -> wideNewsletter()
        10 -> darkMail()
        11 -> longHtml()
        else -> null
    }

    /** The [k]th message of the 12-message thread; the newest one has attachments. */
    fun forThreadMessage(k: Int, last: Boolean): DemoBody = DemoBody(
        text = "Message ${k + 1} of the thread about the trip.\n\n" +
            "Let's meet at https://example.test/trip-plan to decide, or write to " +
            "ana.garcia@example.test.\n" +
            if (k > 0) {
                "\nOn Mon, Jan 1, 2024 at 10:00 AM Ana García <ana.garcia@example.test> wrote:\n" +
                    "> Message $k of the thread.\n> Does Friday work?"
            } else {
                ""
            },
        attachments = if (last) {
            listOf(
                DemoAttachment("Itinerary.pdf", "application/pdf", 482_113),
                DemoAttachment("Hotel.jpg", "image/jpeg", 2_310_457),
                DemoAttachment("Photos.zip", "application/zip", 48_211_300)
            )
        } else {
            emptyList()
        }
    )

    private fun plainWithQuote() = DemoBody(
        text = "Sounds great, see you then.\n\nMore at https://example.test/lunch?id=7, " +
            "or www.example.test/menu (the menu).\n\nAna\n\n" +
            "On Mon, Jan 1, 2024 at 10:00 AM Bob <bob@example.test> wrote:\n" +
            "> Are you free on Friday?\n> I was thinking about 13:00.\n>\n> Bob"
    )

    private fun htmlWithRemoteImage() = DemoBody(
        html = "<div style=\"font-family:sans-serif\"><h2>Weekly digest</h2>" +
            "<p>Hello! Here is what happened. " +
            "<a href=\"https://example.test/read\">Read more</a> " +
            "or <a href=\"https://evil.example.test/login\">https://bank.example.test</a>.</p>" +
            "<img src=\"https://images.example.test/banner.png\" width=\"300\" height=\"80\" " +
            "alt=\"Banner\">" +
            "<img src=\"https://tracker.example.test/open.gif?u=1\" width=\"1\" height=\"1\">" +
            "<p style=\"color:#666\">You receive this because you subscribed.</p></div>"
    )

    private fun spanishReply() = DemoBody(
        text = "De acuerdo, el viernes a las 13:00.\n\nUn abrazo,\nAna\n\n" +
            "El lun, 1 ene 2024 a las 10:00, Bob (<bob@example.test>) escribió:\n\n" +
            "> ¿Nos vemos el viernes?\n> Podría ser a las 13:00."
    )

    private fun gmailQuote() = DemoBody(
        html = "<div dir=\"ltr\">Yes, that works for me.</div><br>" +
            "<div class=\"gmail_quote\"><div dir=\"ltr\" class=\"gmail_attr\">" +
            "On Mon, Jan 1, 2024 " +
            "at 10:00 AM Bob &lt;bob@example.test&gt; wrote:<br></div>" +
            "<blockquote class=\"gmail_quote\" style=\"margin:0px 0px 0px 0.8ex;" +
            "border-left:1px solid #ccc;padding-left:1ex\">Can we move the meeting to Friday?" +
            "</blockquote></div>"
    )

    private fun withAttachments() = DemoBody(
        text = "Here are the files you asked for.",
        attachments = listOf(
            DemoAttachment("Invoice September.pdf", "application/pdf", 182_330),
            DemoAttachment(
                "Budget.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                54_020
            ),
            DemoAttachment("Photos.zip", "application/zip", 48_211_300),
            DemoAttachment(
                "a-file-with-a-very-long-name-that-has-to-wrap-over-several-lines.txt",
                "text/plain",
                812
            )
        )
    )

    private fun inlineImage() = DemoBody(
        html = "<p>The logo below is part of the message (a <code>cid:</code> image).</p>" +
            "<img src=\"cid:pixel@demo\" width=\"96\" height=\"96\" alt=\"Logo\">" +
            "<p>And a file attached.</p>",
        attachments = listOf(
            DemoAttachment(
                "logo.png",
                "image/png",
                pixel.size.toLong(),
                contentId = "pixel@demo",
                inline = true,
                bytes = pixel
            ),
            DemoAttachment("Contract.docx", "application/octet-stream", 90_114)
        )
    )

    private fun longText() = DemoBody(
        text = (1..LONG_PARAGRAPHS).joinToString("\n\n") { n ->
            "Paragraph $n. " +
                "This is a long message to check scrolling and large font sizes. ".repeat(4)
        }
    )

    private fun outlookReply() = DemoBody(
        text = "Thanks, I will have a look.\n\n________________________________\n" +
            "From: Bob <bob@example.test>\nSent: Monday, January 1, 2024 10:00 AM\n" +
            "To: Ana <ana@example.test>\nSubject: Report\n\nPlease review the attached report."
    )

    /**
     * A typical notification mail: a fixed layout table inside padded cells, a card inside it,
     * a remote logo and a button in a table. The paddings add up, so it only fits a phone if the
     * nested widths are limited, not just the outer table.
     */
    private fun notification() = DemoBody(
        html = "<table width=\"100%\" bgcolor=\"#f4f4f4\" cellpadding=\"0\" cellspacing=\"0\">" +
            "<tr><td align=\"center\" style=\"padding:40px 40px\">" +
            "<table width=\"640\" cellpadding=\"0\" cellspacing=\"0\" " +
            "style=\"font-family:Arial;color:#222\">" +
            "<tr><td align=\"center\" style=\"padding:16px\"><img " +
            "src=\"https://images.example.test/logo.png\" width=\"180\" height=\"40\" " +
            "alt=\"Example Cloud\"></td></tr>" +
            "<tr><td style=\"padding:0 32px\"><table width=\"576\" bgcolor=\"#ffffff\" " +
            "cellpadding=\"0\" cellspacing=\"0\" style=\"border:1px solid #ddd\">" +
            "<tr><td style=\"padding:32px\"><h1 style=\"font-size:22px\">" +
            "A new sign-in to your account</h1>" +
            "<p>We noticed a new sign-in from a device we do not recognise. If this was you, " +
            "there is nothing else to do.</p>" +
            "<table cellpadding=\"0\" cellspacing=\"0\"><tr><td bgcolor=\"#1a73e8\" " +
            "style=\"padding:12px 24px\"><a href=\"https://example.test/review\" " +
            "style=\"color:#fff;text-decoration:none\">Review activity</a></td></tr></table>" +
            "<p style=\"font-size:12px;color:#777\">Example Cloud, 1 Example Street.</p>" +
            "</td></tr></table></td></tr></table></td></tr></table>"
    )

    /** A fixed 900px newsletter with a wide image and an unbreakable long URL. */
    private fun wideNewsletter() = DemoBody(
        html = "<table width=\"900\" cellpadding=\"8\" style=\"font-family:Georgia\">" +
            "<tr><td colspan=\"2\"><img src=\"https://images.example.test/hero.jpg\" " +
            "width=\"884\" height=\"300\" alt=\"Autumn sale hero banner\"></td></tr>" +
            "<tr><td width=\"450\"><h2>Autumn sale</h2><p>Everything must go. " +
            "https://example.test/a/very/long/path/that/never/breaks/and/keeps/going/on/and/on/" +
            "forever/and/ever</p></td><td width=\"450\"><p>Second column with more text " +
            "to look at while the layout is wider than the screen.</p></td></tr></table>"
    )

    /** A mail that already is dark: it must not be inverted again. */
    private fun darkMail() = DemoBody(
        html = "<html><head><meta name=\"color-scheme\" content=\"dark\">" +
            "<style>:root{color-scheme:dark}body{background:#101418;color:#e6e6e6}</style>" +
            "</head><body><h2>Already dark</h2><p>This message ships its own dark colours.</p>" +
            "<p><a href=\"https://example.test/x\" style=\"color:#8ab4f8\">A link</a></p>" +
            "</body></html>"
    )

    private fun longHtml() = DemoBody(
        html = (1..LONG_HTML_BLOCKS).joinToString("") { n ->
            "<p>Section $n. " + "A long html message to check height and scrolling. ".repeat(6) +
                "</p>"
        }
    )
}
