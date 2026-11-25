-- Data retention: submissions of a form older than retention_days are deleted automatically.
-- NULL keeps submissions until they are deleted by hand.
ALTER TABLE form ADD COLUMN retention_days integer CHECK (retention_days BETWEEN 1 AND 36500);

CREATE INDEX submission_submitted_at ON submission (submitted_at);
