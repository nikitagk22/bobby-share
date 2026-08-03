import os, sys, glob, json, urllib.request, uuid

MODRINTH_TOKEN = "mrp_1OPTQUaN0LFOIWSCjTzRNfBIrynHM7yBz2j3ikdwhauMZvs53Ub3dQQhDWY4"
CURSEFORGE_TOKEN = "66900a70-f43e-4512-8e76-ffb3a4682243"

MODRINTH_PROJECT_ID = "bdmbv587"
CURSEFORGE_PROJECT_ID = "1613607"

# Modrinth Project IDs
MR_BOBBY_ID = "M08ruV16"
MR_FABRIC_API_ID = "P7dR8mSH"

CHANGELOG = """### 🩹 Bobby Share v1.3.2 - Hotfix & Icon Update

#### 🐛 Bug Fixes
* **Fixed Client Crash on Startup:** Resolved `IllegalArgumentException: Packet type bobbyshare:chunk_request is already registered!` when launching Minecraft client with Fabric API. Duplicate packet registrations in client initializers have been cleaned up.

#### ✨ Features & Improvements
* **Mod Icon Added:** Added official high-resolution pixel art mod icon for Fabric Mod Menu and launcher display.
* **Real-Time Chunk Invalidation:** Full support for real-time block change synchronization from server to clients.
* **1.20.1 – 26.2 Compatibility:** Corrected SemVer dependency ranges across all multi-version legacy ports."""

# CF Game Version IDs
CF_FABRIC = 7499
CF_JAVA_17 = 8326
CF_JAVA_21 = 11135
CF_JAVA_25 = 14454
CF_CLIENT = 9638
CF_SERVER = 9639

CF_VERSIONS = {
    '1.20.1': 9990,
    '1.20.2': 10236,
    '1.20.3': 10395,
    '1.20.4': 10407,
    '1.20.5': 11163,
    '1.20.6': 11198,
    '1.21': 11457,
    '1.21.1': 11779,
    '1.21.2': 12079,
    '1.21.3': 12084,
    '1.21.4': 12281,
    '1.21.5': 12934,
    '1.21.6': 13422,
    '1.21.7': 13506,
    '1.21.8': 13620,
    '1.21.9': 13927,
    '1.21.10': 13964,
    '1.21.11': 14406,
    '26.1': 15933,
    '26.1.1': 16021,
    '26.1.2': 16082,
    '26.2': 16498
}

TARGETS = [
    {
        'file': 'releases/bobbyshare-1.3.2+mc1.20.1.jar',
        'ver': '1.3.2+mc1.20.1',
        'mr_versions': ['1.20.1'],
        'cf_versions': [CF_FABRIC, CF_JAVA_17, CF_CLIENT, CF_SERVER, CF_VERSIONS['1.20.1']]
    },
    {
        'file': 'releases/bobbyshare-1.3.2+mc1.20.2.jar',
        'ver': '1.3.2+mc1.20.2',
        'mr_versions': ['1.20.2'],
        'cf_versions': [CF_FABRIC, CF_JAVA_17, CF_CLIENT, CF_SERVER, CF_VERSIONS['1.20.2']]
    },
    {
        'file': 'releases/bobbyshare-1.3.2+mc1.20.3-1.20.4.jar',
        'ver': '1.3.2+mc1.20.3-1.20.4',
        'mr_versions': ['1.20.3', '1.20.4'],
        'cf_versions': [CF_FABRIC, CF_JAVA_17, CF_CLIENT, CF_SERVER, CF_VERSIONS['1.20.3'], CF_VERSIONS['1.20.4']]
    },
    {
        'file': 'releases/bobbyshare-1.3.2+mc1.20.5-1.20.6.jar',
        'ver': '1.3.2+mc1.20.5-1.20.6',
        'mr_versions': ['1.20.5', '1.20.6'],
        'cf_versions': [CF_FABRIC, CF_JAVA_21, CF_CLIENT, CF_SERVER, CF_VERSIONS['1.20.5'], CF_VERSIONS['1.20.6']]
    },
    {
        'file': 'releases/bobbyshare-1.3.2+mc1.21-1.21.1.jar',
        'ver': '1.3.2+mc1.21-1.21.1',
        'mr_versions': ['1.21', '1.21.1'],
        'cf_versions': [CF_FABRIC, CF_JAVA_21, CF_CLIENT, CF_SERVER, CF_VERSIONS['1.21'], CF_VERSIONS['1.21.1']]
    },
    {
        'file': 'releases/bobbyshare-1.3.2+mc1.21.2-1.21.3.jar',
        'ver': '1.3.2+mc1.21.2-1.21.3',
        'mr_versions': ['1.21.2', '1.21.3'],
        'cf_versions': [CF_FABRIC, CF_JAVA_21, CF_CLIENT, CF_SERVER, CF_VERSIONS['1.21.2'], CF_VERSIONS['1.21.3']]
    },
    {
        'file': 'releases/bobbyshare-1.3.2+mc1.21.4.jar',
        'ver': '1.3.2+mc1.21.4',
        'mr_versions': ['1.21.4'],
        'cf_versions': [CF_FABRIC, CF_JAVA_21, CF_CLIENT, CF_SERVER, CF_VERSIONS['1.21.4']]
    },
    {
        'file': 'releases/bobbyshare-1.3.2+mc1.21.5.jar',
        'ver': '1.3.2+mc1.21.5',
        'mr_versions': ['1.21.5'],
        'cf_versions': [CF_FABRIC, CF_JAVA_21, CF_CLIENT, CF_SERVER, CF_VERSIONS['1.21.5']]
    },
    {
        'file': 'releases/bobbyshare-1.3.2+mc1.21.6-1.21.8.jar',
        'ver': '1.3.2+mc1.21.6-1.21.8',
        'mr_versions': ['1.21.6', '1.21.7', '1.21.8'],
        'cf_versions': [CF_FABRIC, CF_JAVA_21, CF_CLIENT, CF_SERVER, CF_VERSIONS['1.21.6'], CF_VERSIONS['1.21.7'], CF_VERSIONS['1.21.8']]
    },
    {
        'file': 'releases/bobbyshare-1.3.2+mc1.21.9-1.21.10.jar',
        'ver': '1.3.2+mc1.21.9-1.21.10',
        'mr_versions': ['1.21.9', '1.21.10'],
        'cf_versions': [CF_FABRIC, CF_JAVA_21, CF_CLIENT, CF_SERVER, CF_VERSIONS['1.21.9'], CF_VERSIONS['1.21.10']]
    },
    {
        'file': 'releases/bobbyshare-1.3.2+mc1.21.11.jar',
        'ver': '1.3.2+mc1.21.11',
        'mr_versions': ['1.21.11'],
        'cf_versions': [CF_FABRIC, CF_JAVA_21, CF_CLIENT, CF_SERVER, CF_VERSIONS['1.21.11']]
    },
    {
        'file': 'releases/bobbyshare-1.3.2+mc26.1-26.1.2.jar',
        'ver': '1.3.2+mc26.1-26.1.2',
        'mr_versions': ['26.1', '26.1.1', '26.1.2'],
        'cf_versions': [CF_FABRIC, CF_JAVA_25, CF_CLIENT, CF_SERVER, CF_VERSIONS['26.1'], CF_VERSIONS['26.1.1'], CF_VERSIONS['26.1.2']]
    },
    {
        'file': 'releases/bobbyshare-1.3.2+mc26.2.jar',
        'ver': '1.3.2+mc26.2',
        'mr_versions': ['26.2'],
        'cf_versions': [CF_FABRIC, CF_JAVA_25, CF_CLIENT, CF_SERVER, CF_VERSIONS['26.2']]
    }
]

def encode_multipart(fields, files):
    boundary = uuid.uuid4().hex
    body = bytearray()
    
    for k, v in fields.items():
        body.extend(f'--{boundary}\r\n'.encode('utf-8'))
        body.extend(f'Content-Disposition: form-data; name="{k}"\r\n\r\n'.encode('utf-8'))
        body.extend(v.encode('utf-8') if isinstance(v, str) else v)
        body.extend(b'\r\n')
        
    for k, filename, content in files:
        body.extend(f'--{boundary}\r\n'.encode('utf-8'))
        body.extend(f'Content-Disposition: form-data; name="{k}"; filename="{filename}"\r\n'.encode('utf-8'))
        body.extend(b'Content-Type: application/java-archive\r\n\r\n')
        body.extend(content)
        body.extend(b'\r\n')
        
    body.extend(f'--{boundary}--\r\n'.encode('utf-8'))
    return f'multipart/form-data; boundary={boundary}', bytes(body)

def deploy_modrinth(item):
    filepath = item['file']
    filename = os.path.basename(filepath)
    with open(filepath, 'rb') as f:
        file_bytes = f.read()
        
    metadata = {
        "name": filename,
        "version_number": item['ver'],
        "changelog": CHANGELOG,
        "dependencies": [
            { "project_id": MR_BOBBY_ID, "dependency_type": "required" },
            { "project_id": MR_FABRIC_API_ID, "dependency_type": "required" }
        ],
        "game_versions": item['mr_versions'],
        "version_type": "release",
        "loaders": ["fabric"],
        "featured": True,
        "project_id": MODRINTH_PROJECT_ID,
        "file_parts": ["file"]
    }
    
    ct, body = encode_multipart({"data": json.dumps(metadata)}, [("file", filename, file_bytes)])
    req = urllib.request.Request('https://api.modrinth.com/v2/version', data=body, headers={
        'Authorization': MODRINTH_TOKEN,
        'Content-Type': ct
    })
    try:
        res = urllib.request.urlopen(req)
        print(f'[Modrinth SUCCESS] {filename} -> Status {res.status}')
    except Exception as e:
        if hasattr(e, 'read'):
            err_body = e.read().decode('utf-8')
            print(f'[Modrinth ERROR] {filename} -> {e}: {err_body}')
        else:
            print(f'[Modrinth ERROR] {filename} -> {e}')

def deploy_curseforge(item):
    filepath = item['file']
    filename = os.path.basename(filepath)
    with open(filepath, 'rb') as f:
        file_bytes = f.read()
        
    metadata = {
        "changelog": CHANGELOG,
        "changelogType": "markdown",
        "displayName": filename,
        "gameVersions": item['cf_versions'],
        "releaseType": "release",
        "relations": {
            "projects": [
                { "slug": "bobby", "type": "requiredDependency" },
                { "slug": "fabric-api", "type": "requiredDependency" }
            ]
        }
    }
    
    ct, body = encode_multipart({"metadata": json.dumps(metadata)}, [("file", filename, file_bytes)])
    url = f'https://minecraft.curseforge.com/api/projects/{CURSEFORGE_PROJECT_ID}/upload-file'
    req = urllib.request.Request(url, data=body, headers={
        'X-Api-Token': CURSEFORGE_TOKEN,
        'Content-Type': ct
    })
    try:
        res = urllib.request.urlopen(req)
        resp_json = json.loads(res.read().decode('utf-8'))
        print(f'[CurseForge SUCCESS] {filename} -> File ID {resp_json.get("id")}')
    except Exception as e:
        if hasattr(e, 'read'):
            err_body = e.read().decode('utf-8')
            print(f'[CurseForge ERROR] {filename} -> {e}: {err_body}')
        else:
            print(f'[CurseForge ERROR] {filename} -> {e}')

if __name__ == '__main__':
    print('Starting automated deployment of v1.3.2 to Modrinth & CurseForge...\n')
    for item in TARGETS:
        print(f'=== Deploying {item["ver"]} ===')
        deploy_modrinth(item)
        deploy_curseforge(item)
        print()
    print('Deploy completed!')
