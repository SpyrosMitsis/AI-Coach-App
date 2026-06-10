-- Post-workout analysis: cached result of analyze-activity (execution score,
-- target-vs-actual series, splits, AI feedback) so re-opening an activity
-- doesn't refetch streams or re-call the LLM.
alter table completed_activities
  add column if not exists analysis_json jsonb;
