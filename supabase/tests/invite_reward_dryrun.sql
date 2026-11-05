-- 친구 초대 보상 시험(실제 DB 에서 돌려도 아무것도 남지 않음) — 2026-11-05
-- Supabase › SQL Editor 에 통째로 붙여넣고 Run. 마지막에 일부러 오류를 내서 전부 되돌린다.
-- 결과는 오류 메시지 'DRYRUN_RESULT {...}' 안에 있다. 기대값:
--   ledger: 초대한 멤버 +30(새친구1) · 방장 +30(새친구2) — 두 줄만(같은 친구 두 번째 방·30일 된 계정은 없음)
--   notifs: 두 사람에게 '친구 초대 보상 +30코인' / balances: 초대한 멤버 30 · 방장 30
DO $do$
declare
  o uuid := 'aaaaaaaa-1111-4111-8111-000000000001';
  m uuid := 'aaaaaaaa-1111-4111-8111-000000000002';
  n1 uuid := 'aaaaaaaa-1111-4111-8111-000000000003';
  n2 uuid := 'aaaaaaaa-1111-4111-8111-000000000004';
  od uuid := 'aaaaaaaa-1111-4111-8111-000000000005';
  r1 uuid := 'bbbbbbbb-1111-4111-8111-000000000001';
  r2 uuid := 'bbbbbbbb-1111-4111-8111-000000000002';
  res jsonb;
  who text;
begin
  -- ── 마이그레이션(20261105_invite_reward_coins.sql) ──
  alter table public.coin_ledger drop constraint if exists coin_ledger_kind_check;
  alter table public.coin_ledger add constraint coin_ledger_kind_check
    check (kind = any (array['purchase','refund','spend','grant','adjust','ad','invite']));
  create unique index if not exists coin_ledger_invite_ref_uniq on public.coin_ledger (ref) where kind = 'invite';
  insert into public.app_config(key, value) values ('invite_reward_coins', '30') on conflict (key) do nothing;
  alter table app_private.room_join_grants add column if not exists invited_by uuid;
  drop function if exists public.lookup_room_by_code(text);
  create or replace function public.lookup_room_by_code(invite_code text, p_by uuid default null)
  returns table(id uuid, name text, code text, created_at timestamptz)
  language plpgsql security definer set search_path to 'public' as $function$
  declare v_uid uuid := auth.uid();
  begin
    return query
      select case when v_uid is null then null::uuid else r.id end, r.name, r.code, r.created_at
      from public.rooms r where r.code = invite_code limit 1;
    if v_uid is not null then
      insert into app_private.room_join_grants(user_id, room_id, created_at, invited_by)
        select v_uid, r.id, now(), case when p_by is distinct from v_uid then p_by end
        from public.rooms r where r.code = invite_code limit 1
        on conflict (user_id, room_id) do update
          set created_at = excluded.created_at,
              invited_by = coalesce(excluded.invited_by, app_private.room_join_grants.invited_by);
    end if;
  end $function$;
  revoke all on function public.lookup_room_by_code(text, uuid) from public;
  grant execute on function public.lookup_room_by_code(text, uuid) to anon, authenticated, service_role;
  create or replace function app_private.invite_reward_on_join() returns trigger
  language plpgsql security definer set search_path to 'public' as $function$
  declare
    v_created timestamptz; v_owner uuid; v_by uuid; v_inviter uuid; v_amt int; v_id uuid; v_name text;
  begin
    if new.user_id is null or coalesce(new.is_virtual, false) then return new; end if;
    begin
      select u.created_at into v_created from auth.users u where u.id = new.user_id;
      if v_created is null or v_created < now() - interval '7 days' then return new; end if;
      select r.owner_id into v_owner from public.rooms r where r.id = new.room_id;
      if v_owner is null or v_owner = new.user_id then return new; end if;
      if exists (select 1 from public.coin_ledger where kind = 'invite' and ref = 'invite:' || new.user_id) then return new; end if;
      select g.invited_by into v_by from app_private.room_join_grants g where g.user_id = new.user_id and g.room_id = new.room_id;
      if v_by is not null and v_by <> new.user_id
         and exists (select 1 from public.members mm where mm.room_id = new.room_id and mm.user_id = v_by and not coalesce(mm.is_virtual, false)) then
        v_inviter := v_by;
      else
        v_inviter := v_owner;
      end if;
      select coalesce(nullif(value, '')::int, 30) into v_amt from public.app_config where key = 'invite_reward_coins';
      v_amt := coalesce(v_amt, 30);
      if v_amt <= 0 then return new; end if;
      insert into public.coin_ledger(user_id, delta, kind, ref, note)
        values (v_inviter, v_amt, 'invite', 'invite:' || new.user_id, 'friend joined ' || new.room_id)
        on conflict (ref) where kind = 'invite' do nothing returning id into v_id;
      if v_id is not null then
        begin
          select coalesce(nullif(btrim(p.name), ''), '친구') into v_name from public.profiles p where p.id = new.user_id;
          insert into public.notifications(user_id, title, body, type, room_id, actor_user_id, read)
            values (v_inviter, '친구 초대 보상 +' || v_amt || '코인',
                    coalesce(v_name, '친구') || '님이 초대 링크로 들어왔어요. 스토어에서 써 보세요!',
                    'invite_reward', new.room_id, new.user_id, false);
        exception when others then null;
        end;
      end if;
    exception when others then
      raise warning 'invite reward skipped: %', sqlerrm;
    end;
    return new;
  end $function$;
  revoke all on function app_private.invite_reward_on_join() from public, anon, authenticated;
  drop trigger if exists members_invite_reward on public.members;
  create trigger members_invite_reward after insert on public.members
    for each row execute function app_private.invite_reward_on_join();

  -- ── 시험용 사람·방 ──
  insert into auth.users(id, instance_id, aud, role, email, created_at, updated_at) values
    (o,  '00000000-0000-0000-0000-000000000000','authenticated','authenticated','dryrun-owner@example.invalid',  now()-interval '60 days', now()),
    (m,  '00000000-0000-0000-0000-000000000000','authenticated','authenticated','dryrun-member@example.invalid', now()-interval '60 days', now()),
    (n1, '00000000-0000-0000-0000-000000000000','authenticated','authenticated','dryrun-new1@example.invalid',   now(), now()),
    (n2, '00000000-0000-0000-0000-000000000000','authenticated','authenticated','dryrun-new2@example.invalid',   now(), now()),
    (od, '00000000-0000-0000-0000-000000000000','authenticated','authenticated','dryrun-old@example.invalid',    now()-interval '30 days', now());
  insert into public.rooms(id, code, name, owner_id) values (r1, 'ZQ9T01', '시험방', o), (r2, 'ZQ9T02', '시험방2', o);
  insert into public.members(room_id, user_id, color, role) values (r1, o, 'c-vintage', 'owner'), (r1, m, 'c-sky', 'member'), (r2, o, 'c-vintage', 'owner');

  -- ① 새 친구 n1 이 멤버 m 의 링크(by=m)로 방1 입장 — 앱처럼 로그인한 사용자 권한으로
  perform set_config('request.jwt.claims', json_build_object('sub', n1, 'role', 'authenticated')::text, true);
  perform set_config('role', 'authenticated', true);
  perform * from public.lookup_room_by_code('ZQ9T01', m);
  insert into public.members(room_id, user_id, color) values (r1, n1, 'c-mint');
  -- ② 같은 n1 이 방2 에도 입장 — 두 번째 보상 없어야
  perform * from public.lookup_room_by_code('ZQ9T02', m);
  insert into public.members(room_id, user_id, color) values (r2, n1, 'c-mint');
  -- ③ 새 친구 n2 가 코드 직접 입력(by 없음)으로 방1 — 방장이 받아야
  perform set_config('request.jwt.claims', json_build_object('sub', n2, 'role', 'authenticated')::text, true);
  perform * from public.lookup_room_by_code('ZQ9T01');
  insert into public.members(room_id, user_id, color) values (r1, n2, 'c-rose');
  -- ④ 가입 30일 된 계정이 링크로 입장 — 보상 없어야
  perform set_config('request.jwt.claims', json_build_object('sub', od, 'role', 'authenticated')::text, true);
  perform * from public.lookup_room_by_code('ZQ9T01', m);
  insert into public.members(room_id, user_id, color) values (r1, od, 'c-sky');
  perform set_config('role', 'postgres', true);

  select jsonb_build_object(
    'ledger', (select jsonb_agg(jsonb_build_object(
        'to', case user_id when m then '초대한 멤버' when o then '방장' else user_id::text end,
        'delta', delta,
        'for', case ref when 'invite:'||n1 then '새친구1' when 'invite:'||n2 then '새친구2' when 'invite:'||od then '30일된계정' else ref end) order by created_at)
      from public.coin_ledger where kind = 'invite' and user_id in (o, m)),
    'notifs', (select jsonb_agg(jsonb_build_object('to', case user_id when m then '초대한 멤버' when o then '방장' end, 'title', title, 'body', body))
      from public.notifications where type = 'invite_reward' and user_id in (o, m)),
    'grants', (select jsonb_agg(jsonb_build_object('who', case user_id when n1 then '새친구1' when n2 then '새친구2' when od then '30일된계정' end, 'room', case room_id when r1 then '방1' else '방2' end, 'by', case invited_by when m then '멤버' when null then null else invited_by::text end))
      from app_private.room_join_grants where user_id in (n1, n2, od)),
    'joined', (select jsonb_agg(jsonb_build_object('room', case room_id when r1 then '방1' else '방2' end, 'who', case user_id when o then '방장' when m then '멤버' when n1 then '새친구1' when n2 then '새친구2' when od then '30일된계정' end)) from public.members where room_id in (r1, r2)),
    'balances', jsonb_build_object('초대한 멤버', (select coalesce(sum(delta),0) from public.coin_ledger where user_id = m), '방장', (select coalesce(sum(delta),0) from public.coin_ledger where user_id = o))
  ) into res;
  raise exception 'DRYRUN_RESULT %', res;
end
$do$;
