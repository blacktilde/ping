/**
 * Splits a stream of text chunks into lines. Pipe chunks do not respect line boundaries, so
 * the pieces of a partial line are held until its newline arrives.
 *
 * Only each new chunk is searched for a newline. A large response is one line spread over
 * hundreds of pipe chunks, and rescanning an ever-growing buffer on each of them made reading
 * it quadratic: about a second of blocked main process for a 14 MB line.
 */
export class LineSplitter {
  private partial: string[] = []

  /** Feeds a chunk and returns the lines it completed, trimmed, with blank ones dropped. */
  push(chunk: string): string[] {
    const lines: string[] = []
    let start = 0
    let newline = chunk.indexOf('\n')
    while (newline !== -1) {
      this.partial.push(chunk.slice(start, newline))
      const line = this.partial.join('').trim()
      this.partial = []
      if (line) {
        lines.push(line)
      }
      start = newline + 1
      newline = chunk.indexOf('\n', start)
    }
    if (start < chunk.length) {
      this.partial.push(chunk.slice(start))
    }
    return lines
  }
}
