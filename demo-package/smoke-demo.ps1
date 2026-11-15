$ErrorActionPreference = "Stop"
$base = "http://127.0.0.1:18080/api/v1"
Invoke-RestMethod -Method Post "$base/demo/clock/reset" | Out-Null
$payload = @{
    routes = @(@{
        routeIndex = 7
        totalTimeMin = 84
        segments = @(
            @{
                segmentIndex = 1
                mode = "WALK"
                durationMin = 2
                startName = "출발지"
                endName = "동백역.성산마을서해그랑블"
                busOptions = @()
            },
            @{
                segmentIndex = 2
                mode = "BUS"
                durationMin = 66
                startName = "동백역.성산마을서해그랑블"
                endName = "신논현역"
                startLocalStationId = "228000172"
                matchStatus = "MATCHED"
                busOptions = @(@{
                    busNo = "5003B"
                    localRouteId = "228000182"
                })
            }
        )
    })
}
$json = $payload | ConvertTo-Json -Depth 15
$utf8 = [System.Text.Encoding]::UTF8.GetBytes($json)
function Check-Analysis($expectedSeats, $expectedStage) {
    $response = Invoke-RestMethod -Method Post -Uri "$base/routes/analysis" -ContentType "application/json; charset=utf-8" -Body $utf8
    $result = @($response.routes)[0]
    $bus = @($result.segments) | Where-Object { $_.routeLabel -eq "5003B" } | Select-Object -First 1
    if ($null -eq $bus) { throw "5003B 버스 분석 결과 없음" }
    $bus | Select-Object routeLabel,remainSeats,seatType,seatStage,boardingStage,slackMin | Format-List
    if ($bus.remainSeats -ne $expectedSeats -or $bus.boardingStage -ne $expectedStage) {
        throw "예상과 다름: seats=$($bus.remainSeats), risk=$($bus.boardingStage)"
    }
    Write-Host "성공: $($response.evaluatedAt) | $expectedSeats seats / $expectedStage"
}
Write-Host "[08:10 - 0 seats, RISK]"
Check-Analysis 0 "RISK"
Invoke-RestMethod -Method Post "$base/demo/clock/advance?minutes=8" | Out-Null
Write-Host "[08:18 - 8 seats, OK]"
Check-Analysis 8 "OK"
Write-Host "정상: 기존 routes/analysis API가 가상시계와 데모 좌석을 사용합니다."
