package dev.perecollet.mailagent.sync.domain;

/**
 * Converts an HTML body to plain text keeping its line structure. Implemented in the IMAP adapter with Jsoup, so
 * the domain stays free of HTML libraries.
 *
 * <p>Implementations never throw: on HTML they cannot parse they return an empty string, so one malformed message
 * does not fail a whole sync batch.
 */
public interface HtmlTextConverter {

    /** The whole HTML body as text. */
    String toText(String html);

    /**
     * The HTML body as text after removing quote containers ({@code blockquote}, {@code div.gmail_quote}, Outlook
     * {@code #divRplyFwdMsg}…), which is more reliable than guessing quotes from text.
     */
    String toTextWithoutQuotes(String html);
}
