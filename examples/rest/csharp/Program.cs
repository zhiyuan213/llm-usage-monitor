using System.Net.Http.Headers;
using System.Text;

using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(10) };
client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue(
    "Bearer",
    Environment.GetEnvironmentVariable("METERTRAIL_API_KEY")
);

var body = await File.ReadAllTextAsync("examples/batch.json");
using var response = await client.PostAsync(
    Environment.GetEnvironmentVariable("METERTRAIL_ENDPOINT") + "/v1/events/batch",
    new StringContent(body, Encoding.UTF8, "application/json")
);

Console.WriteLine($"{(int)response.StatusCode} {await response.Content.ReadAsStringAsync()}");
return response.StatusCode == System.Net.HttpStatusCode.Accepted ? 0 : 1;
