import type { ConversationStatus } from '@/lib/conversationStatus'

/**
 * Live-turn mark for a sidebar row (4.1.0), rendered in the timestamp slot like
 * ZCode's spinner: a nectar-gathering bee while the turn streams, a pulsing
 * amber honeycomb "H" while an approval/question card waits on the user. Idle
 * rows show neither — the timestamp is their state. Pure display; status
 * derivation lives in {@link conversationStatus}.
 */
export default function ConversationStatusMark({ status, label }: {
  status: ConversationStatus
  label: string
}) {
  if (status === 'running') return <BeeGathering label={label} />
  return (
    <span
      className="conv-status conv-status--input"
      role="img"
      aria-label={label}
      title={label}
    >
      H
    </span>
  )
}

/**
 * 采蜜 (nectar gathering): a bee hovers in a small loop above a honeycomb cell,
 * wings fluttering — the sidebar's "work in progress" signal. All motion is CSS
 * (see .bee-gathering in shell.css); the SVG just provides the shapes. Drawn for
 * legibility at ~32×18 px: fat two-stripe body, one bold wing pair, no filigree.
 */
function BeeGathering({ label }: { label: string }) {
  return (
    <span className="bee-gathering" role="img" aria-label={label} title={label}>
      <svg viewBox="0 0 32 18" width="32" height="18" aria-hidden="true">
        {/* honeycomb cell with a nectar drop — the flower the bee works */}
        <path
          className="bee-gathering__cell"
          d="M25 7.6 20.9 10v4.7L25 17l4.1-2.3V10z"
        />
        <circle className="bee-gathering__nectar" cx="25" cy="12.4" r="1.6" />
        {/* the bee: wings flap, body rides the hover loop (CSS transforms) */}
        <g className="bee-gathering__bee">
          <ellipse className="bee-gathering__wing bee-gathering__wing--far" cx="8" cy="4.9" rx="2.9" ry="2" />
          <ellipse className="bee-gathering__wing bee-gathering__wing--near" cx="12.9" cy="4.6" rx="3.3" ry="2.3" />
          <ellipse className="bee-gathering__body" cx="10.8" cy="10.4" rx="6" ry="4" />
          <path className="bee-gathering__stripe" d="M8.6 7v6.8M13 6.8v7.2" />
          <circle className="bee-gathering__head" cx="4.7" cy="10.2" r="2.4" />
        </g>
      </svg>
    </span>
  )
}
