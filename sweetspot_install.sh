#!/usr/bin/env bash
# Instalacija web aplikacije SweetSpot (dnevnik šećera).
#
# Na Proxmox hostu (kao root):   bash sweetspot_install.sh
#   -> stvara novi Debian LXC kontejner, instalira Docker i pokreće SweetSpot.
#
# Na postojećem Docker hostu (VM ili LXC, kao root):   bash sweetspot_install.sh --local
#   -> postavlja SweetSpot u /opt/sweetspot (Docker instalira samo ako ga nema).
#
# Na Proxmoxu skripta pita gdje pohraniti disk kontejnera i za root lozinku
# (bez pitanja: STORAGE=... ROOT_PASSWORD=...).
#
# Postavke se mogu promijeniti varijablama okoline, npr.:
#   CTID=150 STORAGE=local-zfs IP=192.168.1.60/24 GW=192.168.1.1 PORT=8085 bash sweetspot_install.sh
set -euo pipefail
export PATH="$PATH:/usr/sbin:/sbin"          # pct i srodni alati kad je root dobiven sa "su"
export LANG=C.UTF-8 LC_ALL=C.UTF-8          # bez upozorenja o jeziku u novom kontejneru

SCRIPT_VERSION="dev"                         # pri izdanju se upisuje broj verzije
RELEASES_URL="https://github.com/moordrak/SweetSpot/releases"

SWEETSPOT_VERSION="${SWEETSPOT_VERSION:-latest}"  # oznaka Docker slike, npr. 1.0.0
IMAGE="${IMAGE:-ghcr.io/moordrak/sweetspot:${SWEETSPOT_VERSION}}"
REPO_URL="https://github.com/moordrak/SweetSpot.git"
PORT="${PORT:-8085}"                         # port web sučelja
API_TOKEN="${API_TOKEN:-}"                   # prazno = generira se nasumično
INSTALL_DIR="${INSTALL_DIR:-/opt/sweetspot}"

# Postavke LXC kontejnera (samo na Proxmox hostu)
CTID="${CTID:-}"                             # prazno = sljedeći slobodni ID
CT_HOSTNAME="${CT_HOSTNAME:-sweetspot}"
STORAGE="${STORAGE:-}"                       # prazno = skripta pita pri instalaciji
TEMPLATE_STORAGE="${TEMPLATE_STORAGE:-local}"
BRIDGE="${BRIDGE:-vmbr0}"
IP="${IP:-dhcp}"                             # ili npr. 192.168.1.60/24
GW="${GW:-}"                                 # gateway, potreban uz statičku adresu
CORES="${CORES:-1}"
MEMORY="${MEMORY:-512}"                      # MB
DISK="${DISK:-4}"                            # GB
ROOT_PASSWORD="${ROOT_PASSWORD:-}"           # prazno = skripta pita pri instalaciji

info() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31mGreška:\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || fail "Pokrenite skriptu kao root."

# Pokretanje kroz cijev (npr. "wget -qO- ... | bash"): skripta se sprema u datoteku
# i pokreće iz nje, jer se kasnije kopira u kontejner.
SELF="$(readlink -f "${BASH_SOURCE[0]:-}" 2>/dev/null || true)"
if [ ! -f "$SELF" ]; then
  SELF="$(mktemp /tmp/sweetspot_install.XXXXXX)"
  if [ "$SCRIPT_VERSION" = "dev" ]; then
    url="$RELEASES_URL/latest/download/sweetspot_install.sh"
  else
    url="$RELEASES_URL/download/v$SCRIPT_VERSION/sweetspot_install.sh"
  fi
  curl -fsSL "$url" -o "$SELF" 2>/dev/null || wget -qO "$SELF" "$url" \
    || fail "Ne mogu preuzeti skriptu s $url"
  exec bash "$SELF" "$@"
fi

info "SweetSpot instalacija, verzija skripte: $SCRIPT_VERSION"

# ---------------------------------------------------------------------------
# Instalacija na ovom računalu (Docker host)
# ---------------------------------------------------------------------------
install_local() {
  if ! command -v docker >/dev/null 2>&1; then
    local log=/var/log/sweetspot-docker-install.log
    info "Instaliram Docker (može potrajati nekoliko minuta)..."
    export DEBIAN_FRONTEND=noninteractive
    if ! { apt-get update -qq \
           && apt-get install -y -qq curl ca-certificates \
           && curl -fsSL https://get.docker.com -o /tmp/get-docker.sh \
           && env -u VERSION sh /tmp/get-docker.sh; } >"$log" 2>&1; then
      tail -n 30 "$log" >&2
      fail "Instalacija Dockera nije uspjela. Cijeli zapis: $log"
    fi
    rm -f /tmp/get-docker.sh
  fi
  if docker compose version >/dev/null 2>&1; then
    COMPOSE=(docker compose)
  elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE=(docker-compose)
  else
    fail "Nije pronađen 'docker compose'. Instalirajte paket docker-compose-plugin."
  fi

  info "Postavljam SweetSpot u $INSTALL_DIR..."
  mkdir -p "$INSTALL_DIR/data"

  cat > "$INSTALL_DIR/docker-compose.yml" <<EOF
services:
  sweetspot:
    image: ${IMAGE}
    container_name: sweetspot
    restart: unless-stopped
    ports:
      - "${PORT}:8080"
    environment:
      API_TOKEN: \${API_TOKEN:?Postavite API_TOKEN u datoteci .env}
    volumes:
      - ./data:/data
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://127.0.0.1:8080/api/health"]
      interval: 1m
      timeout: 5s
EOF

  # Token: postojeći se čuva pri ponovnoj instalaciji
  if [ -z "$API_TOKEN" ] && [ -f "$INSTALL_DIR/.env" ]; then
    API_TOKEN="$(sed -n 's/^API_TOKEN=//p' "$INSTALL_DIR/.env" | head -n1)"
  fi
  if [ -z "$API_TOKEN" ]; then
    API_TOKEN="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  fi
  [ "${#API_TOKEN}" -ge 8 ] || fail "API_TOKEN mora imati najmanje 8 znakova."
  printf 'API_TOKEN=%s\n' "$API_TOKEN" > "$INSTALL_DIR/.env"
  chmod 600 "$INSTALL_DIR/.env"

  info "Preuzimam sliku $IMAGE..."
  if ! (cd "$INSTALL_DIR" && "${COMPOSE[@]}" pull -q); then
    info "Slika nije dostupna, gradim je iz izvornog koda..."
    local ref="main"
    [ "$SWEETSPOT_VERSION" = "latest" ] || ref="v${SWEETSPOT_VERSION#v}"
    if ! command -v git >/dev/null 2>&1 && command -v apt-get >/dev/null 2>&1; then
      apt-get update -qq && apt-get install -y -qq git >/dev/null
    fi
    docker build --build-arg "VERSION=${SWEETSPOT_VERSION#v}" -t "$IMAGE" "${REPO_URL}#${ref}:server"
  fi

  info "Pokrećem kontejner..."
  (cd "$INSTALL_DIR" && "${COMPOSE[@]}" up -d)

  local addr
  addr="$(hostname -I 2>/dev/null | awk '{print $1}')"
  cat <<EOF

SweetSpot je instaliran.

  Web sučelje:  http://${addr:-IP-ADRESA}:${PORT}
  Prijava:      korisničko ime proizvoljno, lozinka je token
  Token:        ${API_TOKEN}

U agent/config.ini na PC-u upišite:
  url = http://${addr:-IP-ADRESA}:${PORT}
  token = ${API_TOKEN}

Datoteke: ${INSTALL_DIR}   (baza: ${INSTALL_DIR}/data)
Nadogradnja: ponovno pokrenite ovu skriptu s --local (token i baza ostaju).
EOF
}

# ---------------------------------------------------------------------------
# Stvaranje LXC kontejnera na Proxmox hostu
# ---------------------------------------------------------------------------
# Pitanja se čitaju izravno s terminala (fd 3), pa rade i kad je skripta pokrenuta kroz cijev.
open_tty() {
  { exec 3<>/dev/tty; } 2>/dev/null
}

choose_storage() {
  if [ -n "$STORAGE" ]; then
    info "Spremište zadano varijablom STORAGE: $STORAGE"
    return 0
  fi
  # naziv, vrsta, slobodno (GB) za svako aktivno spremište koje prima kontejnere
  local list
  list="$(pvesm status -content rootdir \
    | awk 'NR>1 && $3=="active" {printf "%s %s %.1f\n", $1, $2, $6/1048576}')"
  [ -n "$list" ] || fail "Nema aktivnog spremišta za kontejnere. Zadajte ga: STORAGE=... bash sweetspot_install.sh"

  local -a names
  mapfile -t names < <(awk '{print $1}' <<<"$list")
  local default="${names[0]}" n
  for n in "${names[@]}"; do
    [ "$n" != "local-lvm" ] || default="local-lvm"
  done

  if [ "${#names[@]}" -eq 1 ]; then
    STORAGE="$default"
    info "Jedino dostupno spremište: $STORAGE"
    return 0
  fi
  if ! open_tty; then
    STORAGE="$default"
    info "Nema terminala pa ne mogu pitati za spremište; koristim $STORAGE."
    return 0
  fi

  local i=0 name type free answer
  printf 'Gdje će biti pohranjen disk kontejnera?\n' >&3
  while read -r name type free; do
    i=$((i + 1))
    printf '  %d) %-16s %-10s slobodno %s GB%s\n' "$i" "$name" "$type" "$free" \
      "$([ "$name" = "$default" ] && echo '   [zadano]')" >&3
  done <<<"$list"
  while true; do
    printf 'Odaberite broj ili naziv (Enter = %s): ' "$default" >&3
    IFS= read -r -u 3 answer || answer=""
    answer="${answer//[[:space:]]/}"
    if [ -z "$answer" ]; then
      STORAGE="$default"
      break
    fi
    if [[ "$answer" =~ ^[0-9]+$ ]] && [ "$answer" -ge 1 ] && [ "$answer" -le "${#names[@]}" ]; then
      STORAGE="${names[$((answer - 1))]}"
      break
    fi
    for n in "${names[@]}"; do
      [ "$n" != "$answer" ] || STORAGE="$n"
    done
    [ -z "$STORAGE" ] || break
    printf 'Nepoznat odabir: %s\n' "$answer" >&3
  done
  exec 3>&-
  info "Odabrano spremište: $STORAGE"
}

ask_root_password() {
  if [ -n "$ROOT_PASSWORD" ]; then
    info "Root lozinka je zadana varijablom ROOT_PASSWORD."
    return 0
  fi
  if ! open_tty; then
    info "Nema terminala pa ne mogu pitati za root lozinku; kontejner ostaje bez nje."
    info "Zadajte je s ROOT_PASSWORD=... ili naknadno: pct exec CTID -- passwd"
    return 0
  fi
  local first second
  while true; do
    printf 'Root lozinka za kontejner (prazno = bez lozinke): ' >&3
    IFS= read -rs -u 3 first || first=""
    printf '\n' >&3
    if [ -z "$first" ]; then
      info "Kontejner će biti bez root lozinke (ulaz samo s hosta: pct enter)."
      break
    fi
    if [ "${#first}" -lt 5 ]; then
      printf 'Lozinka mora imati najmanje 5 znakova.\n' >&3
      continue
    fi
    printf 'Ponovite lozinku: ' >&3
    IFS= read -rs -u 3 second || second=""
    printf '\n' >&3
    if [ "$first" = "$second" ]; then
      ROOT_PASSWORD="$first"
      break
    fi
    printf 'Lozinke se ne podudaraju, pokušajte ponovno.\n' >&3
  done
  exec 3>&-
}

install_proxmox() {
  [ -n "$CTID" ] || CTID="$(pvesh get /cluster/nextid)"
  if pct status "$CTID" >/dev/null 2>&1; then
    fail "Kontejner $CTID već postoji. Odaberite drugi ID: CTID=... bash sweetspot_install.sh"
  fi

  choose_storage
  ask_root_password

  info "Tražim Debian predložak..."
  pveam update >/dev/null || true
  local template
  template="$(pveam list "$TEMPLATE_STORAGE" 2>/dev/null \
    | awk '{print $1}' | grep -E 'debian-1[23]-standard' | sort -V | tail -n1 || true)"
  if [ -z "$template" ]; then
    local name
    name="$(pveam available --section system | awk '{print $2}' \
      | grep -E '^debian-1[23]-standard' | sort -V | tail -n1 || true)"
    [ -n "$name" ] || fail "Nije pronađen Debian predložak (pveam available)."
    info "Preuzimam $name..."
    pveam download "$TEMPLATE_STORAGE" "$name" >/dev/null
    template="$TEMPLATE_STORAGE:vztmpl/$name"
  fi

  local net="name=eth0,bridge=$BRIDGE,ip=$IP"
  [ -z "$GW" ] || net="$net,gw=$GW"

  info "Stvaram kontejner $CTID ($CT_HOSTNAME) na spremištu $STORAGE..."
  pct create "$CTID" "$template" \
    --hostname "$CT_HOSTNAME" \
    --cores "$CORES" --memory "$MEMORY" --swap 256 \
    --rootfs "$STORAGE:$DISK" \
    --net0 "$net" \
    --unprivileged 1 --features nesting=1,keyctl=1 \
    --onboot 1 >/dev/null
  pct start "$CTID"

  if [ -n "$ROOT_PASSWORD" ]; then
    printf 'root:%s\n' "$ROOT_PASSWORD" | pct exec "$CTID" -- chpasswd
    info "Root lozinka je postavljena."
  fi

  info "Čekam mrežu u kontejneru..."
  local i
  for i in $(seq 1 30); do
    if pct exec "$CTID" -- getent hosts deb.debian.org >/dev/null 2>&1; then
      break
    fi
    [ "$i" -lt 30 ] || fail "Kontejner $CTID nema pristup mreži. Provjerite BRIDGE/IP/GW."
    sleep 2
  done

  info "Instaliram SweetSpot u kontejneru..."
  pct push "$CTID" "$SELF" /root/sweetspot_install.sh
  pct exec "$CTID" -- env LANG=C.UTF-8 LC_ALL=C.UTF-8 PORT="$PORT" API_TOKEN="$API_TOKEN" SWEETSPOT_VERSION="$SWEETSPOT_VERSION" IMAGE="$IMAGE" \
    bash /root/sweetspot_install.sh --local

  cat <<EOF

Kontejner: $CTID ($CT_HOSTNAME)   ulaz: pct enter $CTID
EOF
}

case "${1:-}" in
  --local) install_local ;;
  "")
    if command -v pct >/dev/null 2>&1; then
      install_proxmox
    else
      install_local
    fi ;;
  *) fail "Nepoznata opcija: $1 (dopušteno: --local)" ;;
esac
