-- 관리자 회원 검색 v2 — 같은 이름이 여럿일 때 구분되게 가입일(joined_at)·로그인 방법(provider)도 돌려준다(2026-11-26 사용자 "가입일 표시도 적용해줘").
-- 원래 admin_search_users 의 돌려주는 칸을 바꾸려면 함수를 지우고 다시 만들어야 해 Supabase 승인이 필요했는데 두 번 응답이 없어,
-- 사용자가 고른 대로 지우지 않고 새 이름으로 추가(admin.html 은 v2 를 먼저, 안 되면 옛 함수). 검색 규칙·관리자 확인·권한은 옛 함수와 같다. (라이브 적용 2026-11-26)
create or replace function public.admin_search_users_v2(p_q text, p_limit integer default 20)
returns table(id uuid, name text, email text, avatar_url text, joined_at timestamptz, provider text)
language plpgsql
stable security definer
set search_path to ''
as $function$
declare v_q text; v_esc text;
begin
  if auth.uid() is distinct from '3bc13a8b-618d-4d47-abea-1eec4d682ec0'::uuid then
    raise exception 'forbidden' using errcode = '42501';
  end if;
  v_q := trim(coalesce(p_q, ''));
  if length(v_q) < 1 then return; end if;
  v_esc := replace(replace(replace(v_q, '\', '\\'), '%', '\%'), '_', '\_');
  return query
    select p.id, p.name, coalesce(nullif(u.email, ''), p.email)::text, p.avatar_url,
           p.created_at, (u.raw_app_meta_data->>'provider')::text
      from public.profiles p left join auth.users u on u.id = p.id
     where p.name ilike '%' || v_esc || '%' or coalesce(u.email, p.email) ilike '%' || v_esc || '%'
     order by (lower(trim(coalesce(p.name, ''))) = lower(v_q)) desc,
              (coalesce(p.name, '') ilike v_esc || '%') desc,
              p.created_at desc nulls last
     limit greatest(1, least(coalesce(p_limit, 20), 50));
end $function$;

revoke execute on function public.admin_search_users_v2(text, integer) from public, anon;
grant execute on function public.admin_search_users_v2(text, integer) to authenticated, service_role;
