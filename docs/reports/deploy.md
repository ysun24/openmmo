# REPORT — home deployment (Windows PC + Docker Desktop + Pinggy), branch `feat/deploy`

## Built

**Bundle**: `deploy/home/dist/openmmo-home-20261027-e24f9690.zip`, 245 MB (gitignored). It holds the image tar
(`openmmo-image-<v>.tar.gz`, 258 MB gz / 540 MB loaded), `compose.yml`, `.env.example`, the PowerShell
scripts, a Chinese `README.md`, `VERSION` and `SHA256SUMS`. The Windows text files are CRLF. Rebuild it with
`JAVA_HOME=~/.jdks/jdk-25.0.5+8 deploy/home/build-bundle.sh` (`--skip-gradle` reuses the existing installDist).

### A. Packaging (`deploy/home/`)
- `image/Dockerfile`: one `openmmo:<version>` image, linux/amd64, on `eclipse-temurin:25-jre-noble` pinned by digest,
  plus `openssh-client` (apt version pinned). It runs as uid 10001. It holds the game, login and gateway installDists,
  `web/static` with the generated assets, `codegen/dialog`, and decomp emerald/firered/black (minus sound, tools and git),
  which the gateway reads at run time. The JVMs start with no jOOQ banner.
  `entrypoint.sh` takes one of `keys | login | game | gateway | tunnel | invite | version`.
- `compose.yml` (project `openmmo`):
  - Services: `login-db` and `game-db` (`postgres:18@sha256:…`, named volumes, not published), a one-shot `keys`
    service (makes a P-256 keypair in the `keys` volume on the first start and never overwrites it), `login`, `game`,
    `gateway`, `tunnel`.
  - Every service has a healthcheck and uses `depends_on` conditions for start order. All are `restart: unless-stopped`.
    The app containers run read-only, with `cap_drop: ALL`, `no-new-privileges`, memory limits and log rotation.
  - Networks: `backend` is `internal: true` (DBs, login, game, gateway). `edge` carries gateway and tunnel. The only
    host port is `127.0.0.1:${LOCAL_PORT}:8080`.
  - How the game node is reached: the login server advertises `GAME_SERVER_PUBLIC_IPV4`, but `LoginClient` ignores the
    node IP and keeps the login host. So the gateway gets the new `--game-host game`. `GAME_SERVER_PUBLIC_IPV4` stays
    127.0.0.1 because nothing reads it in this setup.
- `image/tunnel.sh`: Pinggy over SSH 443.
  - Options go in the SSH user string: `<token>+b:user:pass+w:cidr…+x:https@pro.pinggy.io`; without a token it uses
    `free@a.pinggy.io`.
  - It reconnects forever with backoff from 2 s up to 60 s, which resets after a session of more than 120 s. It sends
    keepalives (`ServerAliveInterval=30`, `TCPKeepAlive`).
  - It makes its own ed25519 key (`BatchMode`, so SSH never prompts) and keeps trust-on-first-use known_hosts in a volume.
  - It prints `PUBLIC URL: …`. The token and site password are redacted from every log line. `TUNNEL=off` idles;
    `TUNNEL_DRY_RUN=1` prints the redacted command.
  - The gateway's allowed origin is `https://$PINGGY_SUBDOMAIN` when that is set, otherwise one-label wildcards
    `https://*.a.pinggy.link`, `*.a.free.pinggy.link` and `*.pinggy.link`. With `--trust-forwarded`, an origin
    matching `X-Forwarded-Host` is also accepted.
- PowerShell scripts (ASCII only, so Windows PowerShell 5.1 can read them):
  - `common.ps1`: shared helpers.
  - `setup.ps1`: copies the bundle to `%USERPROFILE%\OpenMMO`, writes `.env` and generates any missing secret
    (DB passwords, session secret, admin password, site password), restricts `.env` with icacls, asks for the Pinggy
    token (read as a SecureString), loads the image, runs `up -d`, waits until healthy and prints the URLs.
    It is idempotent and appends settings a newer `.env.example` adds.
  - `start.ps1` (`-WaitForDocker`), `stop.ps1`, `status.ps1`, `logs.ps1`.
  - `invite.ps1` (`-Uses -Days -Count -List -Revoke`; it also prints `https://…/#invite=CODE` links).
  - `backup.ps1`: `pg_dump -Fc` inside each DB container, then `docker cp` (no binary data through PowerShell pipes),
    plus the keys and `.env`. It keeps 14 backups.
  - `restore.ps1`: takes a safety backup first, `pg_restore --clean --single-transaction`, optional `-WithKeys`.
  - `update.ps1`: backup, then load the new image, refresh the files, keep `.env` and the volumes, and recreate.
  - `register-autostart.ps1`: Task Scheduler entry at logon plus 1 min, running `start.ps1 -WaitForDocker`.
- `README.md` (Chinese): Docker Desktop with WSL2, start on login, `powercfg` to stop sleep, Pinggy token and
  subdomain, scp clouddesk → Mac → Windows, setup, invites, daily commands, autostart, backup/restore, update,
  settings, troubleshooting.
- `test/stack_check.py`: checks the origin guard, register, invite reuse and login against a running stack.

### B. Accounts
- **Login protocol** (OpenMMO-only, additive): `RegisterRequestPacket` C2S `0x70` (username, SHA-1 hex, invite) and
  `RegisterResponsePacket` S2C `0x70` (U8 `RegisterResult`). `OpenMmoLoginOpcodes` lists the retail opcodes, and
  `LoginProtocolTest` checks that none of them clash and that the new ones are ≥ 0x70.
- **Login server**:
  - Flyway `V3__invites_and_password_scheme.sql`: the `invites` table (`code, created_by, max_uses, uses, expires_at,
    created_at`) and `users.password_scheme` (default `sha1`), with `password_hash` widened to 128.
  - `PasswordHasher`: PBKDF2-HMAC-SHA256 from the JDK (no new dependency) over the client's SHA-1 hex, 210 000
    iterations, 16-byte salt per user. Comparison is constant time. Unknown users get a dummy hash so timing does not
    reveal them.
  - `sha1` rows still log in and are rewritten to `pbkdf2-sha256` on their next successful login. New accounts and the
    bootstrap admin are stored as pbkdf2.
  - Registration runs in one transaction with `SELECT … FOR UPDATE` on the invite. Rules: username 3–16 characters of
    `[A-Za-z0-9_]`, stored lowercase (case-insensitive unique); the digest must be 40 hex characters; the invite must
    still have uses and not be expired.
  - `LoginThrottle`:
    - per username: 10 failures in 15 min locks it, and the login answers `RATE_LIMITED` with an end time;
    - per address: 120 logins/min and 20 registrations/h;
    - per socket: 5 password attempts and 3 registrations.
  - CLI: `server.login invite create|list|revoke`.
- **Gateway**:
  - New `register` message. It validates the request, applies the 8-character password minimum (only the gateway sees
    the plain password), registers, and then runs the normal login.
  - `--game-host`, and `--trust-forwarded`, which takes the client address from the last `X-Forwarded-For` hop and
    accepts an origin matching `X-Forwarded-Host`.
  - `--allow-origin` accepts one-label wildcards.
  - Per-client-address limits: 30 logins/min and 20 well-formed registrations/h, so typos do not count.
- **Browser**: a "登录 / Log in | 注册 / Register" tab pair. The Register tab adds repeat-password and invite fields,
  `#invite=CODE` links open it with the code filled in, errors are shown in both languages, and on phones the status
  line scrolls into view.
- `client`: `LoginClient.register(...)`.

## How it was verified
- **Tests**: `:protocols.login:test`, `:server.login:test` (including `JooqUserStoreIT` on Postgres 18 via
  testcontainers: sha1→pbkdf2 migration, wrong password leaves the row on sha1, invite use, expiry, revocation, taken
  username), `:client:check`, `:web.gateway:test`, `:web.assets:test` and `:protocols.game:test`, all green.
  `:server.game:test` was not run because server.game is untouched.
- **The bundle itself**:
  - Unzipped, `SHA256SUMS` verified, image removed and reloaded from the tar.
  - The real `.ps1` scripts were run with pwsh 7.4 in a container (`mcr.microsoft.com/powershell@sha256:6230…`) against
    this host's Docker. The compose project was `omtest`, because this host already has unrelated `openmmo_*` volumes.
  - Every `.ps1` passes the PowerShell parser and is ASCII-only.
  - `setup.ps1` generated the secrets, loaded the image and brought every service up healthy. A re-run left `.env`
    byte-identical ("nothing changed").
  - `invite.ps1` create and list, `status.ps1`, `logs.ps1`.
  - `stop.ps1` / `start.ps1`, and a full `compose down` (volumes kept) + `start.ps1`: users and characters were intact,
    and the `keys` service kept the existing keypair.
  - `backup.ps1` wrote both dumps, the keys and `.env`.
  - `restore.ps1 -Force`: after Misty's money was changed and an `intruder` user inserted, the restore reverted both
    and logins still work.
  - `update.ps1` from a newer bundle took a backup, loaded the new image, recreated every app container on the new tag
    and kept the data; the old account still logs in afterwards.
- **Gateway e2e** against the compose stack (`127.0.0.1:8090`): `e2e.py --a yicheng… --b friend…` ended with
  **ALL OK**, three times (before the update, on the stack built by the PowerShell scripts, and after the update).
- **`stack_check.py`**: ALL OK.
  - Origin guard: own origin, random `*.a.pinggy.link` and `*.a.free.pinggy.link` origins and an `X-Forwarded-Host`
    match are accepted; `evil.example` and two-label subdomains are refused with 403.
  - Registration: `BAD_USERNAME`, `BAD_PASSWORD`, register OK with remember token, the used-up invite gives
    `BAD_INVITE`, and the new account logs in with an upper-case username.
  - Gateway logs show the client address taken from the last XFF hop (`client 203.0.113.9`).
- **Password migration**: a legacy row inserted with raw SHA-1 (as in the brief) logged in through the gateway and is
  now `pbkdf2-sha256` (checked in psql). A wrong password is still refused afterwards.
- **Browser screenshots (looked at)**, in `$KIROCREW_SCRATCH/deploy-shots/`:
  - `d1-register-tab.png`: desktop, the invite link opens the Register tab with the code filled in.
  - `d2-ingame.png`: Misty registered and in game.
  - `m1-register.png` / `m2-ingame.png`: mobile (Pixel 7) registration of Brock.
  - `d5-sees-brock.png` / `m5-sees-misty.png`: both in Pallet Town after `/tp`, each one's name tag visible to the
    other; `__game.world.ents` contains the other player.
- **Tunnel**: `TUNNEL_DRY_RUN=1` prints `ssh … -R 0:gateway:8080 <PINGGY_TOKEN>+b:friends:<SITE_PASS>+w:…+x:https@pro.pinggy.io`
  with the token redacted. The reconnect loop and backoff (2→4→8→16 s) were checked against `127.0.0.1:443` only.
  No real Pinggy connection was made.

## Only testable on the Windows PC
- Docker Desktop/WSL2 behaviour: `docker load` of the tar, `icacls` restricting `.env` and `backups\`, Windows
  PowerShell **5.1** (only pwsh 7 was run here), the Task Scheduler task at logon, and the sleep settings.
- Pinggy for real:
  - the `<token>@pro.pinggy.io` user-string syntax with `b:`/`w:`/`x:https`; the docs were reachable only through
    search snippets, since pinggy.io is blocked from this host;
  - that key auth with `BatchMode` is accepted;
  - which URL line Pinggy prints;
  - whether a persistent subdomain bound to the token is used with a plain `-R0:` forward;
  - whether Pinggy sets `X-Forwarded-For` / `X-Forwarded-Host` (if not, all clients share one address bucket and the
    origin must come from `PINGGY_SUBDOMAIN` or the wildcards).
- WebSocket through Pinggy basic auth on phones, especially iOS Safari. The README has a fallback: clear
  `SITE_USER`/`SITE_PASS` and rely on invites and/or `PINGGY_ALLOW_IPS`.
- Free tier: the 60-minute reconnects and the URL changing on each one.

## Not done / notes
- Password ≥ 8 is enforced by the gateway and the browser. The login server only sees the SHA-1 digest and checks its
  format.
- The game/login jars still bundle the build's dev `game.private.pem` resource. The containers always override it with
  `/keys`.
- The decomp subset in the image was not trimmed further (sound, tools and git are excluded), about 150 MB uncompressed.
- `docker compose` here: the compose v2.40.3 plugin, copied from `docker/compose-bin:v2.40.3@sha256:e39da820…`, was
  installed to `~/.docker/cli-plugins/docker-compose` on this host (outside the repo).
- All test containers, volumes and networks (`omtest*`) and the extra images were removed. No processes are left running.

## Files touched outside `deploy/`
`protocols.login` (LoginProtocol.kt, new packets/RegisterPackets.kt, 2 tests) · `server.login` (Main.kt, new InviteCli.kt,
auth/{JooqUserStore, UserService, new PasswordHasher, new LoginThrottle}.kt, di/LoginServerComponent.kt,
handler/LoginAppHandler.kt, new V3 migration, tests) · `client/.../net/LoginClient.kt` · `web/gateway` (build.gradle.kts
adds `:protocols.login`, Gateway.kt, Main.kt, WsSession.kt, TransportTest.kt) · `web/static` (index.html, js/main.js,
css/main.css) · `docs/web/PROTOCOL.md`.

## PROTOCOL changes (additive)
`docs/web/PROTOCOL.md` §11:
- the `register` message and its reply;
- `RATE_LIMITED` on `login`;
- login packets `0x70` C2S/S2C;
- gateway options `--game-host`, `--trust-forwarded` and wildcard `--allow-origin`.
