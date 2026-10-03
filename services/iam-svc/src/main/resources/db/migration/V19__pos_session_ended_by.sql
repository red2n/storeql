-- A supervisor ending a colleague's sign-in session says who and why (till-sessions slice 4).
ALTER TABLE pos_sessions ADD COLUMN ended_by UUID;
ALTER TABLE pos_sessions ADD COLUMN end_reason TEXT;
