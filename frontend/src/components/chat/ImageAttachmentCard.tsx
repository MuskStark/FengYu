import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Eye, X } from 'lucide-react'

/**
 * ZCode-style inline-image card for screenshots pasted into a turn: a cropped
 * thumbnail plus a meta row (name · pixel dimensions · view) instead of the raw
 * image at natural size. The view gesture (and the thumbnail itself) open the
 * full-size lightbox.
 */
export default function ImageAttachmentCard({ name, mimeType, base64Data }: {
  name: string
  mimeType: string
  base64Data: string
}) {
  const { t } = useTranslation()
  const [dimensions, setDimensions] = useState<{ width: number; height: number } | null>(null)
  const [zoomed, setZoomed] = useState(false)
  const src = `data:${mimeType};base64,${base64Data}`

  useEffect(() => {
    // Dimensions are only known after decode; the card renders fine without
    // them and fills the meta row in when the decode finishes.
    const image = new Image()
    image.onload = () => setDimensions({ width: image.naturalWidth, height: image.naturalHeight })
    image.src = src
    return () => { image.onload = null }
  }, [src])

  return (
    <figure className="chat-image-card">
      <button
        type="button"
        className="chat-image-card__thumb"
        title={t('aichat.viewImage')}
        onClick={() => setZoomed(true)}
      >
        <img src={src} alt={name} loading="lazy" draggable={false} />
      </button>
      <figcaption className="chat-image-card__meta">
        <span className="chat-image-card__name" title={name}>{name}</span>
        {dimensions && (
          <span className="chat-image-card__dims cx-muted">
            {dimensions.width}×{dimensions.height}
          </span>
        )}
        <button
          type="button"
          className="cx-btn cx-btn--text cx-btn--sm chat-image-card__view"
          onClick={() => setZoomed(true)}
        >
          <Eye size={13} />{t('aichat.viewImage')}
        </button>
      </figcaption>
      {zoomed && <ImageLightbox src={src} name={name} onClose={() => setZoomed(false)} />}
    </figure>
  )
}

/** Full-size overlay: click anywhere or press Esc to dismiss. */
export function ImageLightbox({ src, name, onClose }: { src: string; name: string; onClose: () => void }) {
  const { t } = useTranslation()
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose() }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="chat-lightbox" role="dialog" aria-modal="true" aria-label={name} onClick={onClose}>
      <img className="chat-lightbox__img" src={src} alt={name} onClick={event => event.stopPropagation()} />
      <button
        type="button"
        className="cx-iconbtn chat-lightbox__close"
        aria-label={t('common.close')}
        onClick={onClose}
      ><X size={16} /></button>
    </div>
  )
}
