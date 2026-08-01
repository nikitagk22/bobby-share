# Bobby Share

[![Minecraft Version](https://img.shields.io/badge/Minecraft-26.2-blue.svg)](https://link.modrinth.com)
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

Choose the Bobby Share jar matching the Minecraft and Bobby versions used by the client:

| Minecraft | Bobby | Bobby Share | Java |
| --- | --- | --- | --- |
| 1.20.5–1.20.6 | 5.2.0–5.2.1 | `bobbyshare-1.2.0+mc1.20.5-1.20.6.jar` | 21 |
| 1.21–1.21.1 | 5.2.4.x | `bobbyshare-1.2.0+mc1.21-1.21.1.jar` | 21 |
| 1.21.2–1.21.3 | 5.2.5.x | `bobbyshare-1.2.0+mc1.21.2-1.21.3.jar` | 21 |
| 1.21.4 | 5.2.6.x | `bobbyshare-1.2.0+mc1.21.4.jar` | 21 |
| 1.21.5 | 5.2.7.x | `bobbyshare-1.2.0+mc1.21.5.jar` | 21 |
| 1.21.6–1.21.8 | 5.2.8–5.2.9 | `bobbyshare-1.2.0+mc1.21.6-1.21.8.jar` | 21 |
| 1.21.9–1.21.10 | 5.2.10.x | `bobbyshare-1.2.0+mc1.21.9-1.21.10.jar` | 21 |
| 1.21.11 | 5.2.11.x | `bobbyshare-1.2.0+mc1.21.11.jar` | 21 |
| 26.1–26.1.2 | 5.2.13.x | `bobbyshare-1.2.0+mc26.1-26.1.2.jar` | 25 |
| 26.2 | 5.2.15.x | `bobbyshare-1.2.0+mc26.2.jar` | 25 |

The ready-to-use jars are located in the `releases` directory. One jar is shared by
multiple Minecraft versions only where the relevant Minecraft, Fabric networking,
chunk serialization, and Bobby integration APIs remain compatible.

### Client-Side
Place the following files in your client's `.minecraft/mods/` directory:
1. `bobby-5.2.15+mc26.2.jar`
2. `bobbyshare-1.2.0+mc26.2.jar`

### Server-Side
Place only the addon jar in your server's `mods/` directory:
1. `bobbyshare-1.2.0+mc26.2.jar`

*(Note: The main Bobby mod is client-side only and should **not** be installed on the server).*

---

## 🛠️ Building

To build the mod from source, you need **Java 25** installed.

Run the Gradle build command inside the project directory:
```bash
./gradlew build
```
The compiled jar will be located in `build/libs/bobbyshare-1.2.0+mc26.2.jar`.

---

## 📄 License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
