# Bobby Share

[![Minecraft Version](https://img.shields.io/badge/Minecraft-1.20.1%20--%2026.2-blue.svg)](https://modrinth.com/mod/bobby-share)
[![Platform](https://img.shields.io/badge/Platform-Fabric-red.svg)](https://fabricmc.net)
[![Modrinth](https://img.shields.io/badge/Modrinth-Release-green.svg)](https://modrinth.com/mod/bobby-share)
[![CurseForge](https://img.shields.io/badge/CurseForge-Release-orange.svg)](https://www.curseforge.com/minecraft/mc-mods/bobby-share)
[![License](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

**Bobby Share** is a high-performance Fabric mod designed to collaboratively stream chunk data from the Minecraft server directly to clients to dynamically fill the rendering cache of the **[Bobby](https://github.com/Johni0702/bobby)** mod.

Developed by **[ngk22](https://github.com/ngk22)**.

---

### 🚀 How It Works
Bobby is a client-side mod that allows you to have a render distance greater than the server's limit by caching chunks locally on your computer. Normally, you have to visit the chunks yourself to cache them.

**Bobby Share** bridges this gap:
1. When you join the server or move into new areas, your client checks if it is missing chunk data in its local Bobby cache.
2. If the chunk is missing, the client sends a lightweight network request to the server.
3. The server loads the chunk data asynchronously (or serializes live active chunks directly from server RAM), optimizes it, and streams it back to you.
4. Your client saves the chunk locally and displays it, allowing you to instantly see far beyond the server's render distance.

---

### 🔄 Real-Time Chunk Cache Invalidation & Seamless Hot-Swapping (v1.3.0+ & v1.4.0)
Never worry about outdated distant view distances or visual holes again!
* **Instant Sync:** Whenever a player breaks a block, places a block, or explodes terrain on the server, Bobby Share invalidates the modified chunk in real time.
* **Zero Overhead:** Sends a tiny 8-byte notification packet to connected clients. 
* **Seamless Hot-Swap Updating (New in v1.4.0):** Modified chunks are updated and injected directly into Bobby's active visual memory on the client without unloading, completely eliminating render flashes, stutter, and gaping black voids.
* **Stale Packet Discard:** Automatically discards out-of-order in-flight responses if a chunk was modified while network packets were in transit.

---

### ⚡ Performance, Safety & Concurrency Optimizations
Designed to scale smoothly from 3 players to 100+ concurrent players without causing TPS drops or clogging network channels:
* **Thread-Safe Live Chunk Streaming (New in v1.4.0):** Actively loaded chunks near players are safely serialized on the server thread before packet dispatch, completely eliminating `PalettedContainer` multi-threading race conditions and server watchdog crashes under heavy exploration.
* **Stress-Tested Under Heavy Traffic:** Rigorously tested serving tens of thousands of real-time chunk requests under heavy player movement with zero crashes or leaks.
* **Server-Side LRU Cache:** Caches up to 4096 optimized chunk compounds in memory (~100MB of RAM) to serve popular regions instantly without repeating disk reads.
* **Throttled Client Queue:** Throttles outgoing client requests to 3 chunks per tick (60 chunks/sec), completely eliminating ping spikes in caves or dense terrain.
* **Token Bucket Rate Limiting:** Limits requests per player (Burst: 200 chunks, Refill: 80 chunks/second) to protect the server from being spammed.
* **NBT Stripping (Bandwidth Optimization):** Strips heavy, rendering-irrelevant data from chunks (structures, entity ticks, carving masks, block entities) before sending, reducing payload sizes by **50% to 80%**.
* **Main-Thread Safety:** Fetches chunk data asynchronously using Minecraft's thread-safe loading APIs. On the client, file writing runs on background threads to keep the game completely lag-free.
* **Safe Decoupling:** Gracefully handles environments where Bobby is missing or client-only without failing mixin injections or crashing the server.

---

### ⚙️ Configuration & Commands (Server-Side)
You can configure Bobby Share's performance settings using the auto-generated config file at `config/bobbyshare.json` on your server:

* `rateLimitBurst` - Maximum chunks a player can request in a quick burst (default: `200`).
* `rateLimitRefill` - Number of chunk requests restored to a player per second (default: `80`).
* `cacheCapacity` - Maximum stripped chunks stored in server RAM (default: `4096`).
* `maxRequestDistance` - Safety limit of how far from the player requested chunks can be (default: `34.0`).
* `blacklistedDimensions` - List of dimension identifiers to disable chunk streaming for (default: `["minecraft:the_end"]`).

#### Admin Commands (Requires OP level 2):
* `/bobbyshare stats` - Displays live server performance dashboard: RAM cache occupancy, hit rate %, live RAM captures, disk reads, rate-limited requests, and invalidations sent.
* `/bobbyshare reload` - Reloads the configuration file from disk and applies changes instantly.
* `/bobbyshare clearcache` - Clears the server-side RAM chunk cache.

---

### 📥 Installation & Setup
To stream chunks successfully, **Bobby Share** must be installed on both the server and client:

* **Client-Side:** Place the `bobbyshare-1.4.0+mc<version>.jar` **AND** the original `bobby-*.jar` in your `.minecraft/mods/` directory.
* **Server-Side:** Place **ONLY** the `bobbyshare-1.4.0+mc<version>.jar` in your server's `mods/` directory. 
  *(WARNING: Do NOT put the original Bobby mod on the server, as it is client-side only and will crash the server on startup).*

Supported Minecraft Versions: **1.20.1 to 26.2** across 13 dedicated version builds available in [GitHub Releases](https://github.com/nikitagk22/bobby-share/releases), [Modrinth](https://modrinth.com/mod/bobby-share), and [CurseForge](https://www.curseforge.com/minecraft/mc-mods/bobby-share).

---

### 🛠️ Building
To build the mod from source for any target Minecraft version:
```bash
./gradlew build
```
Compiled release jars will be located in the `releases/` directory.

---

### 📄 License
This project is licensed under the GNU General Public License v3 - see the [LICENSE](LICENSE) file for details.
