// Small models sometimes wrap "YAML only" output in a code fence despite
// instructions not to — strip it so the editor always holds parseable YAML.
export function stripLeadingFence(text: string): string {
  const m = text.match(/^```[a-zA-Z]*\n/)
  return m ? text.slice(m[0].length) : text
}

export function stripTrailingFence(text: string): string {
  return text.replace(/\n?```\s*$/, '')
}
