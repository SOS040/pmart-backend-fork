#!/usr/bin/env bash
# PC 의 실제 LAN IP 가 placement.conf 의 IP 와 같은지 확인한다(DHCP 라 IP 가 바뀔 수 있다 — README "IP 변경 시 복구").
# watch.sh 가 source 해서 쓴다. 단독 실행(시험용):
#   deploy/lan/ipcheck.sh <ssh 별칭> <배치 IP>                     이름 조회부터 판정까지
#   deploy/lan/ipcheck.sh --classify <ssh 별칭> <배치 IP> <후보,IP>   조회 결과를 주고 판정만
# - 컴퓨터 이름은 ~/.ssh/config 의 HostName(ssh -G). HostName 이 IP 면 확인하지 않는다(SKIP).
# - 조회는 Windows 이름 해석(DNS·LLMNR·NetBIOS). A 레코드 중 PC 1 의 LAN 서브넷(기본 게이트웨이 인터페이스) 안의 주소만 후보로 쓴다
#   — IPv6 링크 로컬(fe80::)·WSL/Hyper-V 가상 어댑터(172.x) 주소를 거른다.
# - 배치 IP 와 다른 후보는 ssh-keyscan 으로 받은 호스트 키를 known_hosts 의 별칭 키와 비교해 같은 PC 인지 확인한다(접속·로그인 없음).
# 결과 한 줄: OK | SKIP | LOOKUP_FAIL | NO_LAN_ADDR <조회된 주소> | CHANGED <새 IP> | UNVERIFIED <후보> | AMBIGUOUS <일치 IP들>
#   이름 조회 문제(LOOKUP_FAIL·NO_LAN_ADDR)는 IP 변경이 아니다. IP 변경 확정은 CHANGED 뿐.

ip2int() { local IFS=.; set -- $1; echo $(( ($1 << 24) + ($2 << 16) + ($3 << 8) + $4 )); }
is_ipv4() { [[ "$1" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]]; }

# 이름들을 한 번에 조회. 출력: "LAN <ip>/<prefix>" 한 줄 + 이름마다 "NAME <이름> <ip,ip|FAIL>"
ipcheck_resolve() {
  local n names=""
  for n in "$@"; do
    [[ "$n" =~ ^[A-Za-z0-9.-]+$ ]] || { echo "NAME $n FAIL"; continue; }
    names+="'$n',"
  done
  local ps; ps=$(cat <<EOF
\$ProgressPreference = 'SilentlyContinue'
\$c = Get-NetIPConfiguration | Where-Object { \$_.IPv4DefaultGateway -and \$_.NetAdapter.Status -eq 'Up' } | Select-Object -First 1
\$a = \$c.IPv4Address | Select-Object -First 1
if (\$a) { "LAN " + \$a.IPAddress + "/" + \$a.PrefixLength }
foreach (\$n in @(${names%,})) {
  try {
    \$r = Resolve-DnsName -Name \$n -Type A -QuickTimeout -ErrorAction Stop | Where-Object { \$_.Type -eq 'A' } | ForEach-Object { \$_.IPAddress } | Sort-Object -Unique
    if (\$r) { "NAME \$n " + (\$r -join ',') } else { "NAME \$n FAIL" }
  } catch { "NAME \$n FAIL" }
}
EOF
)
  # 여러 줄 스크립트를 -Command - 로 넘기면 줄마다 따로 실행되므로 run-ps.sh 처럼 -EncodedCommand 로 넘긴다
  powershell.exe -NoProfile -NonInteractive -EncodedCommand "$(printf %s "$ps" | iconv -f UTF-8 -t UTF-16LE | base64 -w0)" 2>/dev/null | tr -d '\r'
}

# 별칭의 known_hosts ed25519 키와 그 IP 가 내놓는 키가 같은가
ipcheck_same_host() {
  local alias="$1" ip="$2" known seen
  known="$(ssh-keygen -F "$alias" 2>/dev/null | awk '$2=="ssh-ed25519"{print $3; exit}')"
  [ -n "$known" ] || return 1
  seen="$(ssh-keyscan -T 5 -t ed25519 "$ip" 2>/dev/null | awk '$2=="ssh-ed25519"{print $3; exit}')"
  [ -n "$seen" ] && [ "$seen" = "$known" ]
}

# 판정. $1 별칭, $2 배치 IP, $3 조회 결과(쉼표 구분 또는 FAIL), $4 LAN "<ip>/<prefix>"
ipcheck_classify() {
  local alias="$1" want="$2" found="$3" lan="$4" ip net mask cands=() matched=()
  [ "$found" = FAIL ] || [ -z "$found" ] && { echo LOOKUP_FAIL; return; }
  if [ -n "$lan" ]; then
    mask=$(( (0xFFFFFFFF << (32 - ${lan#*/})) & 0xFFFFFFFF )); net=$(( $(ip2int "${lan%/*}") & mask ))
  fi
  for ip in ${found//,/ }; do
    is_ipv4 "$ip" || continue
    [[ "$ip" == 169.254.* ]] && continue
    [ -n "$lan" ] && [ $(( $(ip2int "$ip") & mask )) -ne "$net" ] && continue
    cands+=("$ip")
  done
  [ ${#cands[@]} -eq 0 ] && { echo "NO_LAN_ADDR $found"; return; }
  for ip in "${cands[@]}"; do [ "$ip" = "$want" ] && { echo OK; return; }; done
  for ip in "${cands[@]}"; do ipcheck_same_host "$alias" "$ip" && matched+=("$ip"); done
  case ${#matched[@]} in
    1) echo "CHANGED ${matched[0]}" ;;
    0) echo "UNVERIFIED $(IFS=,; echo "${cands[*]}")" ;;
    *) echo "AMBIGUOUS $(IFS=,; echo "${matched[*]}")" ;;
  esac
}

ipcheck_hostname() { ssh -G "$1" 2>/dev/null | awk '$1=="hostname"{print $2; exit}'; }

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  set -uo pipefail
  if [ "${1:-}" = --classify ]; then
    lan="$(ipcheck_resolve | awk '$1=="LAN"{print $2}')"
    ipcheck_classify "$2" "$3" "$4" "$lan"
  else
    name="$(ipcheck_hostname "${1:?ssh 별칭}")"
    if is_ipv4 "$name"; then echo SKIP; exit 0; fi
    out="$(ipcheck_resolve "$name")"
    ipcheck_classify "$1" "${2:?배치 IP}" "$(awk -v n="$name" '$1=="NAME" && $2==n {print $3}' <<<"$out")" "$(awk '$1=="LAN"{print $2}' <<<"$out")"
  fi
fi
