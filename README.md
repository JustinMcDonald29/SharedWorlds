# SharedWorlds (Fabric)

**Minecraft Fabric client-side mod for cooperative, serialized world hosting**

---

## Overview

This mod enables a **shared, turn-based hosting model** for Minecraft worlds without requiring a dedicated server or always-on host.

The goal is to allow a group of players to:
- Share a single world save via a cloud-sync solution (Google Drive, Syncthing, etc.)
- Ensure **only one player can modify the world at a time**
- Seamlessly take turns hosting the world locally
- Avoid world corruption, conflicted files, or user error
- Continue using modded Minecraft launched via CurseForge or similar tools

This is designed to work well alongside mods like **Essential**, allowing:
- One player to host the world when they are playing
- Other players to join via multiplayer
- A different player to take over hosting later without manual save transfers

---

## Non-Goals

This mod intentionally does **not**:
- Replace dedicated servers
- Handle real-time multi-host synchronization
- Integrate directly with cloud provider APIs
- Modify world save formats
- Add new UI buttons or seriously overhaul Minecraft menus

The design favors **simplicity, safety, and predictability** over automation magic.

---

## High-Level Design

### Core Idea

The mod treats the shared world directory as a **locked resource**.

- A shared world exists **outside** of `.minecraft/saves/`
- A lock file acts as a mutex indicating who is hosting
- When a player attempts to open a locked world:
  - The mod blocks the action
  - Displays lock information (host, timestamp, etc.)
- When unlocked, the world can be opened and hosted locally

### World Lifecycle (Planned)

1. Player selects a shared world in the singleplayer menu
2. Mod intercepts the "Play Selected World" action
3. If locked:
   - Show message explaining who is hosting
   - Prevent world from opening
4. If unlocked:
   - Deep-copy shared world → local working directory
   - Create lock file in shared directory
   - Open the local working copy
5. On game exit:
   - Copy working copy back to shared directory
   - Remove lock file

At no point does Minecraft write directly to the shared/cloud-synced directory.

---

## Lock File

The presence of a lock file indicates that the world is currently in use.

Example structure (subject to change):

```json
{
  "host": "PlayerName",
  "hostUuid": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
  "createdAt": "2025-03-29T21:15:00Z",
  "mcVersion": "1.21.1",
  "modVersion": "0.1.0"
}
```

## Configuration

The mod will read a configuration file describing shared worlds

Example (early concept):

```json
{
  "worlds": [
    {
      "id": "shared_world_1",
      "displayName": "Shared Survival World",
      "sharedPath": "C:/Users/User/Google Drive/MinecraftWorlds/SharedWorld",
      "workingPath": "C:/temp/mc-working/SharedWorld"
    }
  ]
}
```
Configuration will initially be manual and file-based.
A companion setup tool may be added later if needed.

## Intended Compatibility
- **Loader:** Fabric
- **Minecraft:** 1.21.x (currently 1.21.11)
- **Environment:** Client-side only
- **Modpacks:** Compatible with CurseForge/modded launches
- **Sync Tools:** Google Drive, Syncthing, Dropbox, etc. (external)

## Roadmap
### Phase 1 - Foundations
## Roadmap

### Phase 1 — Foundations
- [x] Fabric environment setup
- [x] Mod loads correctly
- [ ] Read config file on startup
- [ ] Log detected shared worlds

### Phase 2 — World Access Control
- [ ] Intercept "Play Selected World"
- [ ] Detect lock file
- [ ] Display lock information to user
- [ ] Prevent opening locked worlds

### Phase 3 — Safe World Copying
- [ ] Copy shared world → local working directory
- [ ] Create lock file on open
- [ ] Open world from working copy

### Phase 4 — Cleanup & Recovery
- [ ] Copy working world back on exit
- [ ] Remove lock file
- [ ] Basic crash detection and recovery
- [ ] Handle stale locks

### Phase 5 — Polish
- [ ] Better UI messaging
- [ ] Validation of shared world directories
- [ ] Improved logging and diagnostics
- [ ] Documentation for setup and usage

