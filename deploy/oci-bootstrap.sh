#!/usr/bin/env bash
#
# Prepare a fresh Oracle Cloud instance to run the Relay stack. Run once, as root, on the box:
#
#   sudo deploy/oci-bootstrap.sh
#
# Everything here is idempotent. What it does NOT do — because none of it is reachable from
# inside the instance — is open the VCN security list, reserve the public IP, or point DNS at it.
# See deploy/README.md § Oracle Cloud for those three.
#
# Target: VM.Standard.A1.Flex, 4 OCPU / 24 GB, Ubuntu 24.04 or Oracle Linux 9, arm64.

set -uo pipefail

if [ -t 1 ]; then
  C_RESET=$'\033[0m'; C_RED=$'\033[31m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_BOLD=$'\033[1m'
else
  C_RESET=; C_RED=; C_GREEN=; C_YELLOW=; C_BOLD=
fi
step() { printf '%s==>%s %s\n' "$C_BOLD" "$C_RESET" "$*"; }
ok()   { printf '  %sok%s   %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn() { printf '  %swarn%s %s\n' "$C_YELLOW" "$C_RESET" "$*"; }
die()  { printf '  %sfail%s %s\n' "$C_RED" "$C_RESET" "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run as root (sudo $0)"

# ── what are we on ──────────────────────────────────────────────────────────────────────────
. /etc/os-release
ARCH="$(uname -m)"
MEM_MB=$(( $(awk '/MemTotal/{print $2}' /proc/meminfo) / 1024 ))

step "host: $PRETTY_NAME, $ARCH, ${MEM_MB} MiB RAM, $(nproc) vCPU"
case "$ID" in
  ubuntu|debian) PKG=apt ;;
  ol|oracle|rhel|centos|almalinux|rocky) PKG=dnf ;;
  *) die "unsupported distro '$ID' — expected Ubuntu 24.04 or Oracle Linux 9" ;;
esac
[ "$ARCH" = "aarch64" ] && ok "Ampere A1 — the images CI pushes are multi-arch, this is fine"

# The declared mem_limits total ~13 GiB, five unbounded Postgres add ~1 GiB, and the host needs
# the rest. Below 16 GiB the ELK containers are what has to go (deploy/README.md § Requirements).
if [ "$MEM_MB" -lt 15000 ]; then
  warn "${MEM_MB} MiB is under the 16 GiB the full stack wants."
  warn "Resize to 4 OCPU / 24 GB — Always Free allows exactly that — or drop the four logging"
  warn "containers. There is no \`logging\` profile in the prod compose file (unlike the dev"
  warn "one): omitting them means naming the other containers on \`up -d\`, and then Kibana is"
  warn "not available to debug the box you just shrank. Resizing is the better trade."
fi

# ── boot volume ─────────────────────────────────────────────────────────────────────────────
# Resizing the volume in the OCI console does NOT grow the filesystem; oci-growfs does. Five
# Postgres, Kafka and an Elasticsearch index outgrow the 47 GiB default quickly.
step "filesystem"
if command -v /usr/libexec/oci-growfs >/dev/null 2>&1; then
  /usr/libexec/oci-growfs -y >/dev/null 2>&1 && ok "boot volume filesystem grown to the volume size"
else
  warn "oci-growfs not found; install oci-utils if you resized the boot volume in the console"
fi
df -h / | tail -1

# ── swap ────────────────────────────────────────────────────────────────────────────────────
# OCI images ship with none. This is insurance against an OOM kill taking the whole stack down,
# not somewhere to run from — the JVMs are sized to fit in RAM.
step "swap"
if swapon --show --noheadings | grep -q .; then
  ok "swap already present"
else
  fallocate -l 4G /swapfile && chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
  ok "4 GiB swapfile added"
fi

# ── kernel ──────────────────────────────────────────────────────────────────────────────────
# Elasticsearch refuses to start below 262144 map counts, and the error names bootstrap checks
# rather than the sysctl. The other two keep the JVMs off swap and let Kafka hold its sockets.
step "sysctl"
cat > /etc/sysctl.d/99-relay.conf <<'EOF'
vm.max_map_count = 262144
vm.swappiness = 10
net.core.somaxconn = 1024
EOF
sysctl --quiet -p /etc/sysctl.d/99-relay.conf && ok "vm.max_map_count=262144 (Elasticsearch will start)"

# ── docker ──────────────────────────────────────────────────────────────────────────────────
step "docker"
if command -v docker >/dev/null 2>&1; then
  ok "already installed: $(docker --version)"
else
  if [ "$PKG" = apt ]; then
    apt-get update -qq
    apt-get install -y -qq ca-certificates curl gnupg
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL "https://download.docker.com/linux/$ID/gpg" -o /etc/apt/keyrings/docker.asc
    chmod a+r /etc/apt/keyrings/docker.asc
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/$ID $VERSION_CODENAME stable" \
      > /etc/apt/sources.list.d/docker.list
    apt-get update -qq
    apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  else
    dnf install -y -q dnf-utils
    dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
    dnf install -y -q docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  fi
  systemctl enable --now docker
  ok "installed: $(docker --version)"
fi
docker compose version >/dev/null 2>&1 || die "docker compose plugin missing"

# Let the deploy user drive docker without sudo — the GitHub Actions deploy job runs
# `docker compose` over a non-interactive ssh session, where sudo would prompt.
for u in ubuntu opc relay; do
  if id "$u" >/dev/null 2>&1 && ! id -nG "$u" | grep -qw docker; then
    usermod -aG docker "$u" && ok "added $u to the docker group (re-login to take effect)"
  fi
done

# ── host firewall ───────────────────────────────────────────────────────────────────────────
#
# THE Oracle Cloud trap. Both the Ubuntu and Oracle Linux images ship a host firewall that
# rejects everything except ssh, so opening the VCN security list is only half the job — and the
# half that fails is not obvious:
#
#   * Bridge-published ports (nginx 80/443, livekit 7881/7882) survive it. Docker DNATs them in
#     PREROUTING and they traverse FORWARD, where Docker inserts DOCKER-USER above the distro's
#     REJECT.
#   * coturn runs `network_mode: host` (Docker's userland proxy mangles the source address of
#     forwarded UDP, which breaks TURN's per-peer permission checks). Its packets hit INPUT and
#     the distro REJECT drops them.
#
# So the symptom of skipping this is precisely the one deploy/README.md's smoke test step 5
# exists to catch: everything works, calls connect, and there is no audio over mobile data.
step "host firewall"
PORTS_TCP="80 443 3478 5349 7881"
PORTS_UDP="3478 5349 7882"
UDP_RANGE="49160:49200"

if command -v firewall-cmd >/dev/null 2>&1 && systemctl is-active --quiet firewalld; then
  for p in $PORTS_TCP; do firewall-cmd --permanent --quiet --add-port="$p/tcp"; done
  for p in $PORTS_UDP; do firewall-cmd --permanent --quiet --add-port="$p/udp"; done
  firewall-cmd --permanent --quiet --add-port="${UDP_RANGE/:/-}/udp"
  firewall-cmd --reload >/dev/null
  ok "firewalld: opened $PORTS_TCP /tcp, $PORTS_UDP /udp, ${UDP_RANGE/:/-}/udp"
else
  # Insert above the catch-all REJECT rather than appending below it, where it would never match.
  insert_at() {
    local n
    n=$(iptables -L INPUT --line-numbers -n | awk '/REJECT|DROP/{print $1; exit}')
    echo "${n:-1}"
  }
  add() {   # add <proto> <dport>
    iptables -C INPUT -p "$1" --dport "$2" -j ACCEPT 2>/dev/null && return 0
    iptables -I INPUT "$(insert_at)" -p "$1" --dport "$2" -j ACCEPT
  }
  for p in $PORTS_TCP; do add tcp "$p"; done
  for p in $PORTS_UDP; do add udp "$p"; done
  add udp "$UDP_RANGE"

  if command -v netfilter-persistent >/dev/null 2>&1; then
    netfilter-persistent save >/dev/null 2>&1 && ok "iptables rules saved (survive reboot)"
  elif [ -d /etc/iptables ]; then
    iptables-save > /etc/iptables/rules.v4 && ok "iptables rules written to /etc/iptables/rules.v4"
  else
    warn "rules added but NOT persisted — install iptables-persistent or they die on reboot"
  fi
  ok "iptables: opened $PORTS_TCP /tcp, $PORTS_UDP /udp, $UDP_RANGE/udp"
fi

# ── what is left, and none of it is doable from in here ─────────────────────────────────────
PRIVATE_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
PUBLIC_IP="$(curl -fsS --max-time 5 http://169.254.169.254/opc/v2/vnics/ -H 'Authorization: Bearer Oracle' 2>/dev/null \
             | grep -o '"publicIp"[^,]*' | head -1 | cut -d'"' -f4)"

cat <<EOF

${C_BOLD}Host is ready. Four things remain, all outside the instance:${C_RESET}

  1. VCN security list / NSG — ingress rules for:
       80,443/tcp   3478,5349/tcp   3478,5349/udp   49160-49200/udp   7881/tcp   7882/udp
     The rules this script just added are the HOST firewall. Both layers must allow it.

  2. Reserve the public IP (Networking -> IP Management). An ephemeral IP changes on every
     stop/start, and it is baked into DNS, the TLS certificate, coturn's --external-ip and
     LiveKit's node_ip.
       this instance:  private ${PRIVATE_IP:-?}   public ${PUBLIC_IP:-?}
     Put the public one in PUBLIC_IP in deploy/.env. The private address on the NIC is correct
     and expected — OCI does 1:1 NAT, which is exactly why PUBLIC_IP is a separate setting.

  3. ONE A record at that IP. The edge is a single nginx vhost routed by path, so API_HOST,
     AUTH_HOST, SFU_HOST and TURN_HOST in deploy/.env all hold that same name. Kibana has no
     hostname at all — it binds 127.0.0.1:5601 and you tunnel to it.

  4. git clone the repo to /opt/relay, then deploy/README.md § First deploy.

EOF
