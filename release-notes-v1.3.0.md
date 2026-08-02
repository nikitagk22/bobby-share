# Bobby Share 1.3.0

Stable release with cache invalidation support across the currently supported Minecraft versions.

Changes:
- Added server-side chunk cache invalidation after successful block changes.
- Clients now mark affected Bobby cached chunks as stale.
- The stale flag is kept if the server response is empty or failed.
- The next successful chunk response refreshes and overwrites the local Bobby cache.
- Ported the same behavior to all supported Minecraft version builds.

Files:
- `bobbyshare-1.3.0+mc1.20.1.jar`
- `bobbyshare-1.3.0+mc1.20.2.jar`
- `bobbyshare-1.3.0+mc1.20.3.jar`
- `bobbyshare-1.3.0+mc1.20.3-1.20.4.jar`
- `bobbyshare-1.3.0+mc1.20.5-1.20.6.jar`
- `bobbyshare-1.3.0+mc1.21-1.21.1.jar`
- `bobbyshare-1.3.0+mc1.21.2-1.21.3.jar`
- `bobbyshare-1.3.0+mc1.21.4.jar`
- `bobbyshare-1.3.0+mc1.21.5.jar`
- `bobbyshare-1.3.0+mc1.21.6-1.21.8.jar`
- `bobbyshare-1.3.0+mc1.21.9-1.21.10.jar`
- `bobbyshare-1.3.0+mc1.21.11.jar`
- `bobbyshare-1.3.0+mc26.1-26.1.2.jar`
- `bobbyshare-1.3.0+mc26.2.jar`
