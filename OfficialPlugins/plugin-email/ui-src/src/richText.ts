import DOMPurify from 'dompurify'

const ALLOWED_TAGS = ['p', 'br', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'strong', 'b', 'em', 'i', 'u',
  'ol', 'ul', 'li', 'a', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'blockquote', 'span']
const SAFE_STYLES = new Set(['text-align', 'color', 'background-color', 'font-size', 'font-weight',
  'font-style', 'text-decoration', 'border', 'border-color', 'border-style', 'border-width'])

export function sanitizeEmailHtml(input: string): string {
  const clean = DOMPurify.sanitize(input ?? '', {
    ALLOWED_TAGS,
    ALLOWED_ATTR: ['href', 'title', 'style'],
    ALLOWED_URI_REGEXP: /^(?:(?:https?|mailto):|[^a-z]|[a-z+.-]+(?:[^a-z+.-:]|$))/i,
  })
  const document = new DOMParser().parseFromString(`<body>${clean}</body>`, 'text/html')
  document.body.querySelectorAll<HTMLElement>('[style]').forEach(element => {
    const declarations = (element.getAttribute('style') ?? '').split(';').flatMap(item => {
      const colon = item.indexOf(':')
      if (colon < 1) return []
      const name = item.slice(0, colon).trim().toLowerCase()
      const value = item.slice(colon + 1).trim()
      return SAFE_STYLES.has(name) && value.length <= 80 && !/url\(|expression|javascript:/i.test(value)
        ? [`${name}: ${value}`]
        : []
    })
    if (declarations.length) element.setAttribute('style', declarations.join('; '))
    else element.removeAttribute('style')
  })
  return document.body.innerHTML
}

export function plainTextFromHtml(html: string): string {
  return new DOMParser().parseFromString(sanitizeEmailHtml(html), 'text/html').body.textContent?.trim() ?? ''
}
