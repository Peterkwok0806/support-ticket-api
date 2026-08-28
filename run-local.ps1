Get-Content "$PSScriptRoot\.env" | ForEach-Object {
    if ($_ -match '^\s*([^#][^=]*)=(.*)$') {
        $name = $matches[1].Trim()
        $value = $matches[2]
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
}

$env:SPRING_PROFILES_ACTIVE = "local"

docker compose up -d
.\mvnw.cmd spring-boot:run