CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE event_types (
  id               TEXT PRIMARY KEY,
  name             TEXT NOT NULL,
  description      TEXT NOT NULL,
  duration_minutes INTEGER NOT NULL CHECK (duration_minutes > 0),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- utc_end is stored explicitly (not GENERATED) because Postgres requires generated-column
-- expressions to be IMMUTABLE, but `timestamptz + interval` is STABLE (DST math depends on
-- session timezone). The application is responsible for setting utc_end = utc_start + duration.
-- The range expression in the exclusion constraint uses only IMMUTABLE functions.
CREATE TABLE scheduled_events (
  id               TEXT PRIMARY KEY,
  event_type_id    TEXT NOT NULL REFERENCES event_types(id),
  utc_start        TIMESTAMPTZ NOT NULL,
  utc_end          TIMESTAMPTZ NOT NULL,
  duration_minutes INTEGER NOT NULL CHECK (duration_minutes > 0),
  subject          TEXT NOT NULL,
  notes            TEXT NOT NULL,
  guest_name       TEXT NOT NULL,
  guest_email      TEXT NOT NULL,
  guest_timezone   TEXT NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

  CONSTRAINT scheduled_events_end_after_start CHECK (utc_end > utc_start),

  CONSTRAINT scheduled_events_no_overlap
    EXCLUDE USING GIST (tstzrange(utc_start, utc_end, '[)') WITH &&)
);

CREATE INDEX scheduled_events_utc_start_idx ON scheduled_events (utc_start);
CREATE INDEX scheduled_events_event_type_idx ON scheduled_events (event_type_id);

CREATE TABLE calendar_config (
  id             SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
  owner_name     TEXT       NOT NULL,
  owner_email    TEXT       NOT NULL,
  owner_timezone TEXT       NOT NULL,
  start_of_day   TIME       NOT NULL,
  end_of_day     TIME       NOT NULL,
  working_days   SMALLINT[] NOT NULL,
  breaks         JSONB      NOT NULL
);
