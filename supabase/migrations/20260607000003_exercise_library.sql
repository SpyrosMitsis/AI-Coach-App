-- ============================================================================
-- Exercise library seed — 56 exercises across the major movement patterns.
-- muscle_groups uses a controlled vocabulary:
--   chest, back, lats, traps, shoulders, biceps, triceps, forearms,
--   quads, hamstrings, glutes, calves, core, full_body
-- equipment: bodyweight | dumbbell | barbell | machine | cable | kettlebell
-- ============================================================================

insert into exercise_library (name, muscle_groups, equipment, is_compound) values
  -- Barbell compounds
  ('Back Squat',            array['quads','glutes','hamstrings','core'], 'barbell',   true),
  ('Front Squat',           array['quads','core','glutes'],             'barbell',   true),
  ('Deadlift',              array['hamstrings','glutes','back','traps'],'barbell',   true),
  ('Romanian Deadlift',     array['hamstrings','glutes','back'],        'barbell',   true),
  ('Bench Press',           array['chest','triceps','shoulders'],       'barbell',   true),
  ('Incline Bench Press',   array['chest','shoulders','triceps'],       'barbell',   true),
  ('Overhead Press',        array['shoulders','triceps','core'],        'barbell',   true),
  ('Barbell Row',           array['back','lats','biceps'],              'barbell',   true),
  ('Pendlay Row',           array['back','lats','traps'],               'barbell',   true),
  ('Hip Thrust',            array['glutes','hamstrings'],               'barbell',   true),
  ('Power Clean',           array['full_body','traps','glutes'],        'barbell',   true),
  ('Barbell Lunge',         array['quads','glutes','hamstrings'],       'barbell',   true),
  -- Dumbbell
  ('Dumbbell Bench Press',  array['chest','triceps','shoulders'],       'dumbbell',  true),
  ('Incline DB Press',      array['chest','shoulders'],                 'dumbbell',  true),
  ('Dumbbell Shoulder Press',array['shoulders','triceps'],              'dumbbell',  true),
  ('Dumbbell Row',          array['back','lats','biceps'],              'dumbbell',  true),
  ('Dumbbell Lunge',        array['quads','glutes','hamstrings'],       'dumbbell',  true),
  ('Goblet Squat',          array['quads','glutes','core'],             'dumbbell',  true),
  ('Dumbbell RDL',          array['hamstrings','glutes'],               'dumbbell',  true),
  ('Lateral Raise',         array['shoulders'],                         'dumbbell',  false),
  ('Rear Delt Fly',         array['shoulders','back'],                  'dumbbell',  false),
  ('Dumbbell Curl',         array['biceps','forearms'],                 'dumbbell',  false),
  ('Hammer Curl',           array['biceps','forearms'],                 'dumbbell',  false),
  ('Dumbbell Skullcrusher',  array['triceps'],                          'dumbbell',  false),
  ('Bulgarian Split Squat', array['quads','glutes','hamstrings'],       'dumbbell',  true),
  ('Dumbbell Pullover',     array['lats','chest'],                      'dumbbell',  false),
  -- Bodyweight
  ('Pull-up',               array['lats','back','biceps'],              'bodyweight',true),
  ('Chin-up',               array['lats','biceps','back'],              'bodyweight',true),
  ('Push-up',               array['chest','triceps','shoulders'],       'bodyweight',true),
  ('Dip',                   array['chest','triceps','shoulders'],       'bodyweight',true),
  ('Bodyweight Squat',      array['quads','glutes'],                    'bodyweight',true),
  ('Pistol Squat',          array['quads','glutes','core'],             'bodyweight',true),
  ('Plank',                 array['core'],                              'bodyweight',false),
  ('Side Plank',            array['core'],                              'bodyweight',false),
  ('Hanging Leg Raise',     array['core'],                              'bodyweight',false),
  ('Mountain Climber',      array['core','full_body'],                  'bodyweight',false),
  ('Burpee',                array['full_body'],                         'bodyweight',true),
  ('Glute Bridge',          array['glutes','hamstrings'],               'bodyweight',false),
  ('Nordic Curl',           array['hamstrings'],                        'bodyweight',true),
  ('Calf Raise',            array['calves'],                            'bodyweight',false),
  ('Inverted Row',          array['back','lats','biceps'],              'bodyweight',true),
  ('Pike Push-up',          array['shoulders','triceps'],               'bodyweight',true),
  -- Machine / cable
  ('Leg Press',             array['quads','glutes','hamstrings'],       'machine',   true),
  ('Leg Extension',         array['quads'],                             'machine',   false),
  ('Leg Curl',              array['hamstrings'],                        'machine',   false),
  ('Lat Pulldown',          array['lats','back','biceps'],              'cable',     true),
  ('Seated Cable Row',      array['back','lats','biceps'],              'cable',     true),
  ('Cable Fly',             array['chest'],                             'cable',     false),
  ('Tricep Pushdown',       array['triceps'],                           'cable',     false),
  ('Face Pull',             array['shoulders','back','traps'],          'cable',     false),
  ('Cable Curl',            array['biceps'],                            'cable',     false),
  ('Chest Press Machine',   array['chest','triceps','shoulders'],       'machine',   true),
  ('Seated Calf Raise',     array['calves'],                            'machine',   false),
  ('Hack Squat',            array['quads','glutes'],                    'machine',   true),
  -- Kettlebell
  ('Kettlebell Swing',      array['glutes','hamstrings','core','back'], 'kettlebell',true),
  ('Turkish Get-up',        array['full_body','core','shoulders'],      'kettlebell',true);
