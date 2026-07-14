export default {
  nav: { compose: '写邮件', batch: '批量发送', contacts: '联系人', archive: '邮件归档', records: '发送记录', accounts: '账户设置' },
  common: { save: '保存', cancel: '取消', delete: '删除', search: '搜索', add: '添加', close: '关闭', loading: '加载中…', none: '无' },
  compose: { title: '写一封邮件', direct: '直接输入地址', contactTags: '按联系人标签群发', from: '发件账户', to: '收件人', cc: '抄送', subject: '主题', bodyPlaceholder: '请输入邮件正文…', attach: '添加附件', review: '检查并发送', separateMessages: '共 {count} 封独立邮件', wordNormalized: '已安全清理并保留 Word 格式。' },
  batch: { title: '按附件标签批量发送', directory: '附件目录', recipientGroups: '发送群组标签', ccGroups: '抄送群组标签', commonAttachments: '公共附件', preview: '邮件预览', ignoredFiles: '忽略文件', skippedTags: '跳过标签', review: '生成并检查 {count} 封邮件' },
  contacts: { title: '联系人', newContact: '新建联系人', manageTags: '管理标签', assignTags: '批量添加标签', name: '姓名', email: '邮箱', notes: '备注' },
  archive: { title: '邮件归档', collect: '开始收取', account: 'IMAP 账户', folder: '文件夹', range: '时间范围', output: '归档目录', processed: '已处理', new: '新增', duplicates: '重复', failed: '失败' },
  records: { title: '发送记录', status: '状态', progress: '进度', mode: '模式', sentAt: '发送时间', partial: '部分失败' },
  accounts: { title: '账户设置', loading: '正在加载账户', newAccount: '添加账户', passwordHelp: '留空则保持已保存密码', smtp: 'SMTP 发件服务器', imap: 'IMAP 收件服务器', test: '测试 SMTP', defaultAccount: '默认发件账户' },
  confirmation: { title: '确认发送', approve: '确认发送', reject: '拒绝', expires: '有效期至 {time}' },
  editor: { toolbar: '格式工具栏', bold: '加粗', italic: '斜体', underline: '下划线', heading: '标题', fontSize: '字号', color: '文字颜色', alignLeft: '左对齐', alignCenter: '居中', alignRight: '右对齐', bullets: '项目符号', numbering: '编号', link: '链接', linkPrompt: '请输入安全的 http、https 或 mailto 链接', table: '表格', clear: '清除格式' },
  errors: { unknown: '邮件操作失败，请重试。', actionFailed: '{action}失败：{detail}' },
}
