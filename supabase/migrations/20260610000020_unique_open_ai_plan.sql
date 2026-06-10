-- ============================================================================
-- One open AI-generated plan per user per date.
--
-- generate-workout and plan-week use a delete-then-insert pattern; two
-- concurrent calls could double-insert. This partial unique index makes the
-- invariant database-enforced (the second writer fails loudly instead of
-- duplicating). Scoped so it does NOT restrict:
--   - completed sessions (history),
--   - locked athlete-fixed sessions,
--   - template-scheduled rows (llm_provider = 'template'),
--   - manually built rows (llm_provider is null),
-- all of which may legitimately share a date (double days).
-- ============================================================================

-- Deduplicate any existing open AI plans first (keep the newest).
delete from planned_workouts p
using planned_workouts q
where p.user_id = q.user_id
  and p.date = q.date
  and p.id <> q.id
  and p.completed = false and q.completed = false
  and p.locked = false and q.locked = false
  and p.llm_provider is not null and p.llm_provider <> 'template'
  and q.llm_provider is not null and q.llm_provider <> 'template'
  and (p.created_at < q.created_at or (p.created_at = q.created_at and p.id < q.id));

create unique index if not exists planned_workouts_one_open_ai_plan
  on planned_workouts (user_id, date)
  where completed = false
    and locked = false
    and llm_provider is not null
    and llm_provider <> 'template';
