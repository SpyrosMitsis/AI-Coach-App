-- ============================================================================
-- Let the athlete pin important coach conversations to the top of their history.
-- ============================================================================

alter table coach_conversations
  add column if not exists pinned boolean not null default false;

-- Pinned-first ordering, then most-recently updated.
create index if not exists coach_conversations_user_pinned_idx
  on coach_conversations (user_id, pinned desc, updated_at desc);
