-- ============================================================================
-- workout_feedback.user_id had no default, so web inserts (which rely on the
-- DB filling the owner, the way completed_activities does since migration 12)
-- hit NOT NULL + the RLS WITH CHECK and failed silently — breaking the
-- autoregulation feedback loop from the web client. Default it from the JWT.
-- ============================================================================

alter table workout_feedback alter column user_id set default auth.uid();
