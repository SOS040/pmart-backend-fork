-- 화면 번호(2026-11-26, 0.21.0 — PC5 DC 백오피스 요청, 사용자 "관리자가 볼 땐 직관적으로 … 짧게 붙인 번호로"). ADR-0070·0085 규칙:
-- 글자 + 숫자, 무작위·겹치지 않음·바뀌지 않음. 시스템 ID(trf_id·wave_id·task_id·departure_id)는 그대로 — 화면 번호는 사람이 보는 값만.
-- TRF T+6, Wave W+4, 작업 K+5, 출발 검증 S+6. 50번 뽑아 빈 번호가 없으면 한 자리 늘린다. 새 행은 열 기본값이 정한다(코드가 넣지 않음).
-- 적용된 파일은 수정하지 않는다.
CREATE OR REPLACE FUNCTION pmart_display_number(p_table text, p_col text, p_prefix text, p_digits integer)
RETURNS text LANGUAGE plpgsql VOLATILE AS $$
DECLARE
  d integer := p_digits;
  tries integer := 0;
  c text;
  hit boolean;
BEGIN
  LOOP
    c := p_prefix || (floor(random() * (power(10, d) - power(10, d - 1)))::bigint + power(10, d - 1)::bigint)::text;
    EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I WHERE %I = $1)', p_table, p_col) INTO hit USING c;
    IF NOT hit THEN
      RETURN c;
    END IF;
    tries := tries + 1;
    IF tries >= 50 THEN
      d := d + 1;
      tries := 0;
    END IF;
  END LOOP;
END $$;

ALTER TABLE transfer ADD COLUMN trf_number varchar(16);
DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT trf_id FROM transfer ORDER BY trf_id LOOP
    UPDATE transfer SET trf_number = pmart_display_number('transfer', 'trf_number', 'T', 6) WHERE trf_id = r.trf_id;
  END LOOP;
END $$;
ALTER TABLE transfer ALTER COLUMN trf_number SET DEFAULT pmart_display_number('transfer', 'trf_number', 'T', 6);
ALTER TABLE transfer ALTER COLUMN trf_number SET NOT NULL;
CREATE UNIQUE INDEX ux_transfer_trf_number ON transfer (trf_number);

ALTER TABLE wave ADD COLUMN wave_number varchar(16);
DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT wave_id FROM wave ORDER BY wave_id LOOP
    UPDATE wave SET wave_number = pmart_display_number('wave', 'wave_number', 'W', 4) WHERE wave_id = r.wave_id;
  END LOOP;
END $$;
ALTER TABLE wave ALTER COLUMN wave_number SET DEFAULT pmart_display_number('wave', 'wave_number', 'W', 4);
ALTER TABLE wave ALTER COLUMN wave_number SET NOT NULL;
CREATE UNIQUE INDEX ux_wave_wave_number ON wave (wave_number);

ALTER TABLE task ADD COLUMN task_number varchar(16);
DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT task_id FROM task ORDER BY task_id LOOP
    UPDATE task SET task_number = pmart_display_number('task', 'task_number', 'K', 5) WHERE task_id = r.task_id;
  END LOOP;
END $$;
ALTER TABLE task ALTER COLUMN task_number SET DEFAULT pmart_display_number('task', 'task_number', 'K', 5);
ALTER TABLE task ALTER COLUMN task_number SET NOT NULL;
CREATE UNIQUE INDEX ux_task_task_number ON task (task_number);

ALTER TABLE departure ADD COLUMN departure_number varchar(16);
DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT departure_id FROM departure ORDER BY departure_id LOOP
    UPDATE departure SET departure_number = pmart_display_number('departure', 'departure_number', 'S', 6) WHERE departure_id = r.departure_id;
  END LOOP;
END $$;
ALTER TABLE departure ALTER COLUMN departure_number SET DEFAULT pmart_display_number('departure', 'departure_number', 'S', 6);
ALTER TABLE departure ALTER COLUMN departure_number SET NOT NULL;
CREATE UNIQUE INDEX ux_departure_departure_number ON departure (departure_number);
