-- Per-account SMTP socket timeout (connection/read/write) and per-task delivery progress.
-- 30s default: intranet gateways and anti-tarpit SMTP servers can hold the banner well past
-- the old hardcoded 10s; batch sends now persist sent/total counts for live progress.
ALTER TABLE FENGYU_PL_Email_Account ADD COLUMN smtp_timeout_seconds INTEGER NOT NULL DEFAULT 30;
ALTER TABLE FENGYU_PL_Email_Pending_Send ADD COLUMN sent_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE FENGYU_PL_Email_Pending_Send ADD COLUMN total_count INTEGER NOT NULL DEFAULT 0;
INSERT INTO FENGYU_PL_Email_Schema_History(version) VALUES (7);
