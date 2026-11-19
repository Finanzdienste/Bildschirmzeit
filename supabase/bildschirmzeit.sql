-- Bildschirmzeit: wie lange Boxenstopp, Freizeit, Workout und die
-- Bewerbungszentrale heute offen waren.
--
-- Projekt „Finanzdienste's Project“ (vjyohppohmvhhxgwouti), dasselbe wie
-- Workout und Freizeit. Eingespielt als Migration „bildschirmzeit“; diese Datei
-- ist die Quelle. Geprüft von tests/test-sql.mjs (PGlite, ohne Netz).
--
-- Wer schreibt, wer liest:
--   * Die Widget-App (Android) erzeugt einmal eine geheime Kennung (43 Zeichen
--     base64url) und meldet sie hier an (bildschirmzeit_anmelden).
--   * Ihr „Verbinden“-Knopf öffnet eine der vier Web-Apps mit
--     #bildschirmzeit=<Kennung>. Ab dann meldet diese App ihren Tagesstand
--     (bildschirmzeit_melden) – Sekunden je Tag, nicht Zuwächse.
--   * Das Widget liest den Stand mit derselben Kennung (bildschirmzeit_lesen).
--   * „Alle Daten löschen“ in der Widget-App nimmt Anmeldung und Zahlen weg
--     (bildschirmzeit_loeschen). Danach antwortet bildschirmzeit_melden mit
--     false, und die Web-Apps trennen sich von selbst.
--
-- Gespeichert wird nur der SHA-256 der Kennung. Auf die Tabellen führt mit dem
-- öffentlichen Schlüssel kein Weg; nur diese vier Funktionen.

create table if not exists public.bildschirmzeit_geraet (
  geraet    text primary key,                 -- sha256(Kennung), hex
  angelegt  timestamptz not null default now(),
  gelesen   timestamptz                       -- wann das Widget zuletzt las
);

create table if not exists public.bildschirmzeit (
  geraet       text not null references public.bildschirmzeit_geraet (geraet) on delete cascade,
  app          text not null check (app in ('boxenstopp', 'freizeit', 'workout', 'bewerbungszentrale')),
  tag          date not null,                  -- Kalendertag auf dem Handy
  sekunden     integer not null check (sekunden between 0 and 86400),
  aktualisiert timestamptz not null default now(),
  primary key (geraet, app, tag)
);

-- Welche Apps melden dürfen – auch für eine schon angelegte Tabelle (die
-- Bewerbungszentrale kam am 19.11.2026 dazu). Dieselbe Liste in
-- bildschirmzeit_melden, in web/bildschirmzeit.js (BZ_APPS) und in der
-- Widget-App (WebApp); tests/test-web.mjs prüft, dass alle vier gleich sind.
alter table public.bildschirmzeit drop constraint if exists bildschirmzeit_app_check;
alter table public.bildschirmzeit add constraint bildschirmzeit_app_check
  check (app in ('boxenstopp', 'freizeit', 'workout', 'bewerbungszentrale'));

alter table public.bildschirmzeit_geraet enable row level security;
alter table public.bildschirmzeit enable row level security;
revoke all on table public.bildschirmzeit_geraet from public, anon, authenticated;
revoke all on table public.bildschirmzeit from public, anon, authenticated;

-- Die Kennung nie im Klartext: gespeichert und verglichen wird ihr Hash.
create or replace function public.bildschirmzeit_hash(p_kennung text)
returns text
language plpgsql
immutable
set search_path = ''
as $$
begin
  if p_kennung is null or p_kennung !~ '^[A-Za-z0-9_-]{32,128}$' then
    raise exception 'Kennung ungültig' using errcode = '22023';
  end if;
  return encode(pg_catalog.sha256(pg_catalog.convert_to(p_kennung, 'UTF8')), 'hex');
end
$$;

-- Die Widget-App meldet ihre Kennung an. Mehrfach ist egal.
create or replace function public.bildschirmzeit_anmelden(p_kennung text)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
begin
  insert into public.bildschirmzeit_geraet (geraet)
  values (public.bildschirmzeit_hash(p_kennung))
  on conflict (geraet) do nothing;
  return true;
end
$$;

-- Eine Web-App meldet ihre Tagesstände: p_tage = {"2026-11-14": 1234, …}.
-- Je Tag gilt der größere Wert – doppelt oder in falscher Reihenfolge
-- geschickt schadet nicht. Tage außerhalb von „heute − 8 … heute + 1“ (UTC)
-- und Werte, die keine Zahl sind, fallen still weg. false heißt: Diese
-- Kennung ist nicht (mehr) angemeldet.
create or replace function public.bildschirmzeit_melden(p_kennung text, p_app text, p_tage jsonb)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
  g text := public.bildschirmzeit_hash(p_kennung);
  heute date := (pg_catalog.now() at time zone 'utc')::date;
  t record;
  d date;
begin
  if p_app is null or p_app not in ('boxenstopp', 'freizeit', 'workout', 'bewerbungszentrale') then
    raise exception 'App unbekannt' using errcode = '22023';
  end if;
  if p_tage is null or jsonb_typeof(p_tage) <> 'object' then
    raise exception 'Tage fehlen' using errcode = '22023';
  end if;
  if (select count(*) from jsonb_object_keys(p_tage)) > 10 then
    raise exception 'Zu viele Tage' using errcode = '22023';
  end if;
  if not exists (select 1 from public.bildschirmzeit_geraet where geraet = g) then
    return false;
  end if;

  for t in select key, value from jsonb_each(p_tage) loop
    continue when t.key !~ '^\d{4}-\d{2}-\d{2}$' or jsonb_typeof(t.value) <> 'number';
    begin
      d := t.key::date;
    exception when others then
      continue;
    end;
    continue when d < heute - 8 or d > heute + 1;
    insert into public.bildschirmzeit as b (geraet, app, tag, sekunden)
    values (g, p_app, d, least(86400, greatest(0, round((t.value)::numeric)))::integer)
    on conflict (geraet, app, tag) do update
      set sekunden = greatest(b.sekunden, excluded.sekunden),
          aktualisiert = pg_catalog.now();
  end loop;

  -- Mehr als zwei Monate braucht das Widget nicht.
  delete from public.bildschirmzeit where geraet = g and tag < heute - 62;
  return true;
end
$$;

-- Das Widget liest: alle Tage von p_von bis p_bis (höchstens 62 Tage).
create or replace function public.bildschirmzeit_lesen(p_kennung text, p_von date, p_bis date)
returns table (app text, tag date, sekunden integer, aktualisiert timestamptz)
language plpgsql
security definer
set search_path = ''
as $$
#variable_conflict use_column
declare
  g text := public.bildschirmzeit_hash(p_kennung);
begin
  if p_von is null or p_bis is null or p_bis < p_von or p_bis - p_von > 62 then
    raise exception 'Zeitraum ungültig' using errcode = '22023';
  end if;
  update public.bildschirmzeit_geraet set gelesen = pg_catalog.now() where geraet = g;
  return query
    select b.app, b.tag, b.sekunden, b.aktualisiert
    from public.bildschirmzeit b
    where b.geraet = g and b.tag between p_von and p_bis
    order by b.tag, b.app;
end
$$;

-- „Alle Daten löschen“ in der Widget-App: Anmeldung und Zahlen weg.
create or replace function public.bildschirmzeit_loeschen(p_kennung text)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
begin
  delete from public.bildschirmzeit_geraet where geraet = public.bildschirmzeit_hash(p_kennung);
  return found;
end
$$;

revoke all on function public.bildschirmzeit_hash(text) from public, anon, authenticated;
revoke all on function public.bildschirmzeit_anmelden(text) from public;
revoke all on function public.bildschirmzeit_melden(text, text, jsonb) from public;
revoke all on function public.bildschirmzeit_lesen(text, date, date) from public;
revoke all on function public.bildschirmzeit_loeschen(text) from public;
grant execute on function public.bildschirmzeit_anmelden(text) to anon, authenticated;
grant execute on function public.bildschirmzeit_melden(text, text, jsonb) to anon, authenticated;
grant execute on function public.bildschirmzeit_lesen(text, date, date) to anon, authenticated;
grant execute on function public.bildschirmzeit_loeschen(text) to anon, authenticated;

notify pgrst, 'reload schema';
