# SharedWorlds (Fabric)

**Minecraft Fabric client-side mod for cooperative, serialized world hosting**

---
[Build and Run guide](#build-and-run)
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

### Phase 1 — Foundations
- [x] Fabric environment setup
- [x] Mod loads correctly


### Phase 2 - Basic Access Behaviour (fully local)
- [x] Detection of lock file in local save
- [ ] Intercept Play on local file
- [ ] Detect lock file on close
- [ ] Log lock information

### Phase 3 - World Access Control (shared)
- [ ] Intercept "Play Selected World"
- [ ] Detect lock file
- [ ] Display lock information to user
- [ ] Prevent opening locked worlds
- [ ] Read config file on startup
- [ ] Log detected shared worlds

### Phase 4 - Safe World Copying
- [ ] Copy shared world → local working directory
- [ ] Create lock file on open
- [ ] Open world from working copy

### Phase 5 - Cleanup & Recovery
- [ ] Copy working world back on exit
- [ ] Remove lock file
- [ ] Basic crash detection and recovery
- [ ] Handle stale locks

### Phase 6 - Polish
- [ ] Better UI messaging
- [ ] Validation of shared world directories
- [ ] Improved logging and diagnostics
- [ ] Documentation for setup and usage

# Build and Run
### IDE
It is highly recommended to use Intellij Idea for working on this project, [as stated](https://docs.fabricmc.net/develop/getting-started/setting-up) by the Fabric creators themselves.  Within Idea it is recommended to use the [Minecraft Development](https://plugins.jetbrains.com/plugin/8327-minecraft-development) plugin.  

### Guides and Reference
[Fabric Developer Guides](https://docs.fabricmc.net/develop/)
[Youtube Getting Started Guide](https://www.youtube.com/watch?v=oU8-qV-ZtUY) We won't follow the same project settings, but will give you an idea of what everything should look like
[Generating Minecraft's Source Code](https://docs.fabricmc.net/develop/getting-started/generating-sources) Especially useful, can also be found at 11:50 in the Youtube guide.

### Build
Building should be relatively straightforward, going to the build tab should initiate the build on it's own.  Build config is already set up, you'll just need to ensure you have the right JDKs set up: for Minecraft/Fabric 1.21.x that is [JDK 21](https://www.oracle.com/ca-en/java/technologies/downloads/#java21).
![alt text](/README_images/idea_build.png "Build pane")

**Set your JDK and Language Level to 21 under File->Project Structure**
![alt text](/README_images/idea_jdk.png "JDK selection")

**Set your JDK for Gradle to 21 under File->Settings**
![alt text](/README_images/idea_gradle.png "Gradle settings")
### Run
Running is similarly simple, as long as the build completed successfully, pressing the run button with "Minecraft Client" selected will launch the game with the mod loaded.

![alt text](/README_images/idea_run.png "Run button")
