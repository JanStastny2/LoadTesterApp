ALTER TABLE test_run
ADD COLUMN test_scenario varchar(16) check ( test_scenario in ('STEADY','RAMP_UP'));
