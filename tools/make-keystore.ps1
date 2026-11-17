# Creates the key that signs release builds. Run once. BACK UP the keystore folder and keystore.properties:
# an update can only be installed over an existing install if it is signed with the same key.
. "$PSScriptRoot\env.ps1"
$root = (Resolve-Path "$PSScriptRoot\..").Path
$dir = Join-Path $root 'keystore'
$jks = Join-Path $dir 'willbolt-release.jks'
$props = Join-Path $root 'keystore.properties'

if (Test-Path $jks) { Write-Host "Keystore already exists: $jks"; exit 0 }
# keystore.properties may point at a key with another file name (this one started as keystore/bulwark-release.jks).
if (Test-Path $props) { Write-Host "keystore.properties already exists - keeping the key it points to."; exit 0 }
New-Item -ItemType Directory -Force $dir | Out-Null

$bytes = New-Object byte[] 24
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
$pw = [Convert]::ToBase64String($bytes).Replace('+', 'A').Replace('/', 'b').Replace('=', '')

& keytool -genkeypair -keystore $jks -alias willbolt -keyalg RSA -keysize 4096 -validity 36500 `
    -storepass $pw -keypass $pw -dname 'CN=Willbolt, O=Willbolt'
if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }

@('storeFile=keystore/willbolt-release.jks', "storePassword=$pw", 'keyAlias=willbolt', "keyPassword=$pw") |
    Set-Content -Path $props -Encoding ASCII
Write-Host "Created $jks and $props - back both up somewhere safe."
