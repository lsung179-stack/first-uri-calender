-- 친구 초대 보상 30코인 (2026-11-05 사용자 "친구를 초대하면 코인 30개를 지급")
-- 결정(사용자): 초대한 사람만 받는다 · 초대 링크로 새로 가입한(7일 이내) 친구만 인정 · 받는 횟수 제한 없음.
-- 지급은 서버만 한다(앱은 코인을 직접 주지 않음 — 광고 보상과 같은 원칙).
--   누가 초대했나 = 초대 링크의 &by=<보낸 사람 id>(새 앱) → lookup_room_by_code(invite_code, p_by) 가 입장 허가에 함께 적어 둠.
--   by 가 없거나(옛 링크·코드 직접 입력) 그 방 멤버가 아니면 = 방장.
-- 금액은 app_config.invite_reward_coins(기본 30) — 재빌드 없이 바꿀 수 있다.

-- 1) 코인 장부 종류에 'invite' 추가 + 친구 한 명당 한 번만(ref = 'invite:' || 들어온 친구 id)
alter table public.coin_ledger drop constraint if exists coin_ledger_kind_check;
alter table public.coin_ledger add constraint coin_ledger_kind_check
  check (kind = any (array['purchase','refund','spend','grant','adjust','ad','invite']));
create unique index if not exists coin_ledger_invite_ref_uniq on public.coin_ledger (ref) where kind = 'invite';

insert into public.app_config(key, value) values ('invite_reward_coins', '30') on conflict (key) do nothing;

-- 2) 입장 허가에 '누가 보낸 링크인지' 함께 적기
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

-- 3) 방에 실제 멤버가 새로 들어오면 — 새 가입자(7일 이내)·방장 아님·처음 한 번이면 초대한 사람에게 코인
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
    if v_owner is null or v_owner = new.user_id then return new; end if;   -- 자기 방 만들기는 초대가 아님
    if exists (select 1 from public.coin_ledger where kind = 'invite' and ref = 'invite:' || new.user_id) then return new; end if;
    select g.invited_by into v_by from app_private.room_join_grants g where g.user_id = new.user_id and g.room_id = new.room_id;
    if v_by is not null and v_by <> new.user_id
       and exists (select 1 from public.members m where m.room_id = new.room_id and m.user_id = v_by and not coalesce(m.is_virtual, false)) then
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
      begin   -- 알림이 실패해도 코인은 남게(따로 감싼다)
        select coalesce(nullif(btrim(p.name), ''), '친구') into v_name from public.profiles p where p.id = new.user_id;
        insert into public.notifications(user_id, title, body, type, room_id, actor_user_id, read)
          values (v_inviter, '친구 초대 보상 +' || v_amt || '코인',
                  coalesce(v_name, '친구') || '님이 초대 링크로 들어왔어요. 스토어에서 써 보세요!',
                  'invite_reward', new.room_id, new.user_id, false);
      exception when others then null;
      end;
    end if;
  exception when others then
    -- 보상이 실패해도 방 입장은 막지 않는다
    raise warning 'invite reward skipped: %', sqlerrm;
  end;
  return new;
end $function$;
revoke all on function app_private.invite_reward_on_join() from public, anon, authenticated;

drop trigger if exists members_invite_reward on public.members;
create trigger members_invite_reward after insert on public.members
  for each row execute function app_private.invite_reward_on_join();
