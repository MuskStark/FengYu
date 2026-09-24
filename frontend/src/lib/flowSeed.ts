/**
 * Hand-off channel for "send this input into a Flow" (the chat composer's @-panel flow pick).
 * The composer writes the seed before navigating to the flow; FlowBuilder consumes it on
 * mount and pre-fills the docked Flow chat. sessionStorage keeps it to the tab session —
 * a stale seed from yesterday's tab never hijacks a fresh flow open.
 */
export const FLOW_CHAT_SEED_KEY = 'fengyu:flow-chat-seed:v1'

export interface FlowChatSeed {
  workflowId: string
  text: string
  at: number
}
