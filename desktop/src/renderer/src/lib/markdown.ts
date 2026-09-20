/**
 * Markdown for request and collection notes.
 *
 * Notes come from files that are committed and shared, and from imports of other people's
 * collections, so they are untrusted text. The renderer is configured so it cannot emit
 * markup that was not produced by markdown itself:
 *
 * - `html: false` turns raw HTML in the source into escaped text, so `<script>` and
 *   `<img onerror=...>` are shown, not run.
 * - markdown-it's default link validator refuses `javascript:`, `vbscript:` and most `data:`
 *   URLs, leaving them as plain text.
 * - Links are marked `rel="noopener noreferrer"`. The shell already stops the window from
 *   navigating and opens only http(s) links in the system browser.
 * - The page's CSP blocks remote images and scripts as a second line of defence.
 */

import MarkdownIt from 'markdown-it'

const md = new MarkdownIt({ html: false, linkify: false, typographer: false, breaks: false })

const defaultLinkOpen =
  md.renderer.rules.link_open ??
  ((tokens, index, options, _env, self) => self.renderToken(tokens, index, options))

md.renderer.rules.link_open = (tokens, index, options, env, self) => {
  tokens[index].attrSet('rel', 'noopener noreferrer')
  return defaultLinkOpen(tokens, index, options, env, self)
}

/** @returns HTML that is safe to insert with `{@html}`; empty input gives an empty string. */
export function renderMarkdown(source: string): string {
  return source.trim() ? md.render(source) : ''
}
