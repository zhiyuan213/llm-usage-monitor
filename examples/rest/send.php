<?php
$curl = curl_init(getenv('METERTRAIL_ENDPOINT') . '/v1/events/batch');
curl_setopt_array($curl, [
    CURLOPT_POST => true,
    CURLOPT_POSTFIELDS => file_get_contents('examples/batch.json'),
    CURLOPT_HTTPHEADER => [
        'Authorization: Bearer ' . getenv('METERTRAIL_API_KEY'),
        'Content-Type: application/json',
    ],
    CURLOPT_RETURNTRANSFER => true,
    CURLOPT_TIMEOUT => 10,
]);

$body = curl_exec($curl);
if ($body === false) {
    throw new RuntimeException(curl_error($curl));
}

$status = curl_getinfo($curl, CURLINFO_HTTP_CODE);
echo $status . ' ' . $body . PHP_EOL;
curl_close($curl);

if ($status !== 200) {
    exit(1);
}
