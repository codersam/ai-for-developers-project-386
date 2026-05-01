INSERT INTO calendar_config
  (id, owner_name, owner_email, owner_timezone, start_of_day, end_of_day, working_days, breaks)
VALUES
  (1, 'Alex Owner', 'owner@example.com', 'Europe/Berlin',
   '09:00', '17:00',
   ARRAY[1,2,3,4,5]::SMALLINT[],
   '[]'::JSONB);
