# Development Environment Setup

> Verified working on: Ubuntu 22.04 (Linux Mint 21.3 Virginia)

## Requirements

| Component | Version | Notes |
|-----------|---------|-------|
| Java | JDK 8 (1.8.0_xxx) | **Must be Java 8** - other versions fail |
| sbt | 1.8.2+ | Scala build tool |
| PostgreSQL | 14+ | Database |

## Installation (Ubuntu/Mint)

### 1. Java 8
```bash
sudo apt update
sudo apt install -y openjdk-8-jdk

# Set as default
sudo update-alternatives --set java /usr/lib/jvm/java-8-openjdk-amd64/jre/bin/java
sudo update-alternatives --set javac /usr/lib/jvm/java-8-openjdk-amd64/bin/javac

# Verify
java -version
# Should show: openjdk version "1.8.0_xxx"
```

### 2. sbt
```bash
echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | sudo tee /etc/apt/sources.list.d/sbt.list
curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x99E82A75642AC823" | sudo apt-key add -
sudo apt update
sudo apt install -y sbt
```

### 3. PostgreSQL
```bash
sudo apt install -y postgresql postgresql-contrib
sudo systemctl start postgresql
sudo systemctl enable postgresql

# Create database and user
sudo -u postgres psql -c "CREATE USER psforever WITH PASSWORD 'psforever';"
sudo -u postgres psql -c "CREATE DATABASE psforever OWNER psforever;"
```

## Building & Running

### First Compile
```bash
cd PSF-LoginServer
sbt compile
```
- Takes ~2-3 minutes on first run (downloads dependencies)
- Expect some warnings (unused imports, non-exhaustive matches) - these are fine

### Run Server
```bash
sbt "server/run"
```

**Expected behavior:**
- Terminal stays open with live debug output
- Shows player connections, actions, etc. in real-time
- Does NOT return to prompt - this is correct

**Expected startup output:**
```
PSForever Server - PSForever Project
Login server is running on 127.0.0.1:51000
```

### Known Startup Errors (IGNORE)
```
ERROR akka.actor.SupervisorStrategy - null
java.lang.NullPointerException: null
    at net.psforever.objects.guid.GUIDTask$RegisterObjectTask.action(GUIDTask.scala:44)
    ...
    at net.psforever.objects.serverobject.shuttle.OrbitalShuttlePadControl...
```
These are **HART shuttle (orbital pad) initialization errors** - known issue, doesn't affect gameplay or our bot work.

## Server Ports

| Port | Protocol | Purpose |
|------|----------|---------|
| 51000 | TCP | Login server |
| 51001 | UDP | World server |

## Connecting a Client

1. Use PlanetSide client version 3.15.84.0
2. Point client at server IP (127.0.0.1 for local)
3. Create account on first login (auto-created)

## Running in Background

```bash
# Start in background
nohup sbt "server/run" > server.log 2>&1 &

# View logs
tail -f server.log

# Find and kill
ps aux | grep sbt
kill <PID>
```

## Troubleshooting

### "authentication method 10 not supported"
PostgreSQL version too old or password encryption mismatch. Upgrade PostgreSQL or check `postgresql.conf`.

### Java version errors
Make sure `java -version` shows 1.8.x. Use `update-alternatives` to switch if needed.

### Port already in use
```bash
sudo lsof -i :51000
# Kill the process using the port
```
