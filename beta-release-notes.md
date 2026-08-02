## Bobby Share 1.2.1-beta.1

Beta support for older Minecraft versions using the matching Bobby releases.

### Files

| File | Minecraft | Java | Bobby |
| --- | --- | --- | --- |
| `bobbyshare-1.2.0+mc1.20.1.jar` | 1.20.1 | Java 17 | Bobby 5.0.1.1 |
| `bobbyshare-1.2.0+mc1.20.2.jar` | 1.20.2 | Java 17 | Bobby 5.1.0 |
| `bobbyshare-1.2.0+mc1.20.3-1.20.4.jar` | 1.20.3–1.20.4 | Java 17 | Bobby 5.1.0 |

### Changes

- Added version-specific support for Minecraft 1.20.1–1.20.4.
- Preserved the same chunk request queue, rate limiting, distance checks, dimension blacklist, cache, NBT optimization, and Bobby cache saving as newer versions.
- Added legacy Fabric networking compatibility for these Minecraft versions.

### Notes

- Install the matching Bobby version listed above.
- These files are beta builds and are published separately from the stable release.
- Fabric API and Fabric Loader must match the selected Minecraft version.
