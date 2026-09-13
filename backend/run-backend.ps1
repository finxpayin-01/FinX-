Get-Content .env | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith("#")) {
        $parts = $line.Split("=", 2)
        if ($parts.Count -eq 2) {
            $key = $parts[0].Trim()
            $val = $parts[1].Trim()
            Set-Item -Path "env:$key" -Value $val
        }
    }
}
Write-Host "Connecting to: $env:SPRING_DATASOURCE_URL with user: $env:SPRING_DATASOURCE_USERNAME"
java -jar target\finx-backend-1.0.0.jar
