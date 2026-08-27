param(
    [string]$OutFile = 'target/q1q10-result.json',
    [string]$QuestionFile = 'test-data/user-manual-questions-10.json',
    [string]$Base = 'http://127.0.0.1:18080'
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$h = @{ 'Content-Type' = 'application/json' }

function PostJson($uri, $obj) {
    $body = $obj | ConvertTo-Json -Depth 6
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    $resp = Invoke-WebRequest -Uri $uri -Method Post -Headers $h -Body $bytes -TimeoutSec 900
    $text = [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
    $text | ConvertFrom-Json
}

$login = PostJson "$Base/api/auth/login" @{ username = 'admin'; password = 'admin123' }
if ($login.code -ne 0) { throw "login failed: $($login.message)" }
$h['Authorization'] = "Bearer $($login.data.token)"
$h['X-Workspace-Id'] = '1'
"LOGIN_OK user=$($login.data.username) base=$Base"

$qs = Get-Content -Raw -Encoding UTF8 $QuestionFile | ConvertFrom-Json
$results = @()
foreach ($item in $qs) {
    $sess = PostJson "$Base/api/chat/session" @{ kbId = 7 }
    if ($sess.code -ne 0) { throw "session create failed: $($sess.message)" }
    $sid = $sess.data.id
    $startUtc = [DateTime]::UtcNow.ToString('yyyy-MM-dd HH:mm:ss.fff')
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $ask = PostJson "$Base/api/chat/session/$sid/ask" @{ question = $item.q }
    $sw.Stop()
    $endUtc = [DateTime]::UtcNow.ToString('yyyy-MM-dd HH:mm:ss.fff')
    if ($ask.code -ne 0) {
        $results += [pscustomobject]@{ n = $item.n; cat = $item.cat; sessionId = $sid; question = $item.q; answer = "ERROR: $($ask.message)"; refs = ''; intent = ''; ms = $sw.ElapsedMilliseconds; startUtc = $startUtc; endUtc = $endUtc }
        "Q$($item.n) FAILED in $($sw.ElapsedMilliseconds)ms: $($ask.message)"
    } else {
        $results += [pscustomobject]@{ n = $item.n; cat = $item.cat; sessionId = $sid; question = $item.q; answer = $ask.data.answer; refs = ($ask.data.refs | ConvertTo-Json -Depth 5 -Compress); intent = $ask.data.intent; ms = $sw.ElapsedMilliseconds; startUtc = $startUtc; endUtc = $endUtc }
        "Q$($item.n) done in $($sw.ElapsedMilliseconds)ms intent=$($ask.data.intent) chars=$($ask.data.answer.Length)"
    }
    Start-Sleep -Seconds 2
}

$results | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 $OutFile
"ALL_DONE total=$($results.Count) -> $OutFile"
