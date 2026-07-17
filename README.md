# Bobby Share

[![Minecraft Version](https://img.shields.io/badge/Minecraft-1.20.5%20--%201.21.4+-blue.svg)](https://link.modrinth.com)
[![Platform](https://img.shields.io/badge/Platform-Fabric-red.svg)](https://fabricmc.net)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

**Bobby Share** is a high-performance Fabric mod designed to collaboratively stream chunk data from the Minecraft server directly to clients to dynamically fill the rendering cache of the **[Bobby](https://github.com/Johni0702/bobby)** mod.

Developed by **[ngk22](https://github.com/ngk22)**.

---

## 🚀 How It Works

Bobby is a client-side mod that allows you to have a render distance greater than the server's limit by caching chunks locally on your computer. However, you normally have to visit the chunks yourself to cache them.

**Bobby Share** bridges this gap:
1. When you join the server or move into new areas, your client checks if it is missing chunk data in its local Bobby cache.
2. If the chunk is missing, the client sends a lightweight network request to the server.
3. The server loads the chunk data asynchronously, optimizes it, and streams it back to you.
4. Your client saves the chunk locally, allowing you to instantly see far beyond the server's render distance.

---

## ⚡ Performance & Safety Optimizations

Designed to scale smoothly from 3 players to 100+ concurrent players without causing TPS drops or clogging network channels:

* **Server-Side LRU Cache:** Caches up to 4096 optimized chunk compounds in memory (utilizing ~100MB of RAM) to serve popular regions instantly without repeating disk reads.
* **Token Bucket Rate Limiting:** Limits requests per player (Burst: 200 chunks, Refill: 80 chunks/second) to protect the server from being spammed by bots or speed-flying.
* **NBT Stripping (Bandwidth Optimization):** Strips heavy, rendering-irrelevant data from chunks (structures, entity ticks, carving masks, block entities) before sending, reducing average compressed payload sizes by **50% to 80%**.
* **Main-Thread Safety:** Fetches chunk data asynchronously using Minecraft's thread-safe loading APIs. On the client, file writing runs on background worker threads to keep the rendering engine completely lag-free.
* **Memory Leak Protection:** Cleans up rate-limiting maps and client-side request queues immediately on player disconnect.

---

## ⚙️ Configuration & Commands (Server-Side)

You can configure Bobby Share's performance settings using the auto-generated config file at `config/bobbyshare.json` on your server:

* `rateLimitBurst` - Maximum chunks a player can request in a quick burst (default: `200`).
* `rateLimitRefill` - Number of chunk requests restored to a player per second (default: `80`).
* `cacheCapacity` - Maximum stripped chunks stored in server RAM (default: `4096`).
* `maxRequestDistance` - Safety limit of how far from the player requested chunks can be (default: `34.0`).
* `blacklistedDimensions` - List of dimension identifiers to disable chunk streaming for (default: `["minecraft:the_end"]`).

### Admin Commands (Requires OP level 2):
* `/bobbyshare reload` - Reloads the configuration file from disk and applies changes instantly.
* `/bobbyshare clearcache` - Clears the server-side RAM chunk cache.

---

## 📥 Installation

### Client-Side
Place the following files in your client's `.minecraft/mods/` directory:
1. `bobby-5.2.4.1+mc1.21.jar` (or any compatible Bobby mod version)
2. `bobbyshare-1.1.0.jar`

### Server-Side
Place only the addon jar in your server's `mods/` directory:
1. `bobbyshare-1.1.0.jar`

*(Note: The main Bobby mod is client-side only and should **not** be installed on the server).*

---

## 🛠️ Building

To build the mod from source, you need **Java 21** installed.

Run the Gradle build command inside the project directory:
```bash
./gradlew build
```
The compiled jar will be located in `build/libs/bobbyshare-1.1.0.jar`.

---

## 📄 License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
