-- Unified multi-sport goals: races become "goals & races". Each goal carries a
-- sport and an optional free-text target ("4:45/km", "FTP 260W", "Squat 120kg").
alter table races add column if not exists sport text not null default 'run';
alter table races add column if not exists target text;
