#!/usr/bin/env python3
"""Mint a per-worker internal token from WORKER_TOKEN_PEPPER.

Usage:
  python3 scripts/mint-worker-token.py <pepper> <worker-id>

The control service keeps the pepper. Workers receive only the derived token
as WORKER_SERVICE_TOKEN. Do not give workers the pepper.
"""

import hashlib
import hmac
import sys


def main() -> None:
    if len(sys.argv) != 3:
        print("usage: python3 scripts/mint-worker-token.py <pepper> <worker-id>", file=sys.stderr)
        sys.exit(2)
    pepper, worker_id = sys.argv[1], sys.argv[2]
    digest = hmac.new(pepper.encode("utf-8"), f"WORKER:{worker_id}".encode("utf-8"), hashlib.sha256).hexdigest()
    print(f"mp_wk_{worker_id}_{digest}")


if __name__ == "__main__":
    main()
