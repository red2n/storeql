#!/usr/bin/env bash
#
# The platform administrator's six-digit sign-in code (20.12), from PLATFORM_ADMIN_TOTP_SECRET in
# .env — for a local stack when no authenticator app is at hand. RFC 6238 (HMAC-SHA-1, 30 seconds,
# six digits): the same code an authenticator app shows for that key. A code works once, and the
# server also takes the one just before and just after it.
#
# Usage: scripts/platform-code.sh           the code now, how long it lasts, and the next one
#        scripts/platform-code.sh --setup   the account, setup key and otpauth:// link, to add the
#                                           key to an authenticator app once instead
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$ROOT/.env" ] || { echo "no .env at $ROOT — copy .env.example and fill it in" >&2; exit 2; }
exec python3 - "$ROOT/.env" "${1:-}" <<'PY'
import base64, hashlib, hmac, re, struct, sys, time

env, mode = sys.argv[1], sys.argv[2]
text = open(env, encoding='utf-8').read()


def var(name):
    m = re.search(r'^' + name + r'=(.*)$', text, re.M)
    return m.group(1).strip().strip('"').strip("'") if m else None


secret = (var('PLATFORM_ADMIN_TOTP_SECRET') or '').replace(' ', '').upper()
email = var('PLATFORM_ADMIN_EMAIL') or 'the platform administrator'
if not secret:
    sys.exit('PLATFORM_ADMIN_TOTP_SECRET is not set in .env')
if mode == '--setup':
    print(f'account    {email}')
    print(f'setup key  {secret}   (time-based, six digits, every 30 seconds)')
    print(f'link       otpauth://totp/StoreQL:{email}?secret={secret}&issuer=StoreQL')
    sys.exit(0)
if mode:
    sys.exit(f'unknown option {mode}: use no option for the code, or --setup')
key = base64.b32decode(secret + '=' * (-len(secret) % 8))


def code(step):
    digest = hmac.new(key, struct.pack('>Q', step), hashlib.sha1).digest()
    offset = digest[-1] & 15
    return f"{(struct.unpack('>I', digest[offset:offset + 4])[0] & 0x7FFFFFFF) % 1000000:06d}"


now = time.time()
step = int(now // 30)
left = 30 - int(now) % 30
print(f'{code(step)}   good for {left} more seconds; then {code(step + 1)}')
PY
