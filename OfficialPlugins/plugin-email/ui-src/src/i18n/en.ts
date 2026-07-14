export default {
  nav: { compose: 'Compose', batch: 'Batch Send', contacts: 'Contacts', archive: 'Archive', records: 'Send Records', accounts: 'Account Settings' },
  common: { save: 'Save', cancel: 'Cancel', delete: 'Delete', search: 'Search', add: 'Add', close: 'Close', loading: 'Loading…', none: 'None' },
  compose: { title: 'Compose email', direct: 'Direct addresses', contactTags: 'Contact tags', from: 'From account', to: 'To', cc: 'CC', subject: 'Subject', bodyPlaceholder: 'Write your message…', attach: 'Add attachment', review: 'Review and send', separateMessages: '{count} separate messages', wordNormalized: 'Word formatting was cleaned and preserved safely.' },
  batch: { title: 'Send by attachment tag', directory: 'Attachment directory', recipientGroups: 'Sending group tags', ccGroups: 'CC group tags', commonAttachments: 'Common attachments', preview: 'Message preview', ignoredFiles: 'Ignored files', skippedTags: 'Skipped tags', review: 'Generate and review {count} messages' },
  contacts: { title: 'Contacts', newContact: 'New contact', manageTags: 'Manage tags', assignTags: 'Assign tags', name: 'Name', email: 'Email', notes: 'Notes' },
  archive: { title: 'Mail archive', collect: 'Collect now', account: 'IMAP account', folder: 'Folder', range: 'Date range', output: 'Archive directory', processed: 'Processed', new: 'New', duplicates: 'Duplicates', failed: 'Failed' },
  records: { title: 'Send records', status: 'Status', progress: 'Progress', mode: 'Mode', sentAt: 'Sent at', partial: 'Partially failed' },
  accounts: { title: 'Account settings', loading: 'Loading accounts', newAccount: 'Add account', passwordHelp: 'Leave blank to keep the saved password', smtp: 'SMTP server', imap: 'IMAP server', test: 'Test SMTP', defaultAccount: 'Default sending account' },
  confirmation: { title: 'Confirm send', approve: 'Confirm send', reject: 'Reject', expires: 'Expires {time}' },
  errors: { unknown: 'Email operation failed. Try again.', actionFailed: '{action} failed: {detail}' },
}
