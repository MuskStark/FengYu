/**
 * Hand-off channel for "send this input into a Flow" (the chat composer's @-panel flow pick).
 * The composer writes the seed before navigating to the flow; FlowBuilder consumes it on
 * mount and pre-fills the docked Flow chat. sessionStorage keeps it to the tab session —
 * a stale seed from yesterday's tab never hijacks a fresh flow open.
 */
export const FLOW_CHAT_SEED_KEY = 'fengyu:flow-chat-seed:v1'

export interface FlowChatSeed {
  /** The flow the seed targets; null = a brand-new canvas (/flows/new). */
  workflowId: string | null
  text: string
  at: number
  /** True = the source was an explicit "build it" action: consume by SENDING, not prefilling. */
  autoSend?: boolean
}
