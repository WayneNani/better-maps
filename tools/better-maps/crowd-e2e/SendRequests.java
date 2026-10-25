import app.organicmaps.sdk.crowdtraffic.CrowdTrafficSender;
import app.organicmaps.sdk.crowdtraffic.RelayEndpoints;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Sends the requests written by the C++ client (crowd_traffic_tests --filter=CrowdTrafficE2E, one hex line
 * per bundle) through the OHTTP relay, with the sender of the app (android/sdk, app.organicmaps.sdk.crowdtraffic).
 *
 * Usage: java SendRequests <relay base URL> <requests file>
 * Prints the results and exits with 1 unless every bundle was accepted.
 */
public final class SendRequests
{
  public static void main(String[] args) throws Exception
  {
    final RelayEndpoints endpoints = RelayEndpoints.parse(args[0]);
    if (endpoints == null)
      throw new IllegalArgumentException("invalid relay URL " + args[0]);
    final CrowdTrafficSender sender =
        new CrowdTrafficSender(endpoints, System::currentTimeMillis, m -> System.err.println("[sender] " + m));
    final List<String> lines = Files.readAllLines(Path.of(args[1]), StandardCharsets.US_ASCII);
    final Map<CrowdTrafficSender.Result, Integer> results = new EnumMap<>(CrowdTrafficSender.Result.class);
    for (String line : lines)
    {
      if (line.isBlank())
        continue;
      final byte[] request = new byte[line.length() / 2];
      for (int i = 0; i < request.length; ++i)
        request[i] = (byte) Integer.parseInt(line.substring(2 * i, 2 * i + 2), 16);
      results.merge(sender.send(request), 1, Integer::sum);
    }
    System.out.println("results: " + results);
    final int accepted = results.getOrDefault(CrowdTrafficSender.Result.ACCEPTED, 0);
    System.out.println("accepted " + accepted);
    System.exit(accepted > 0 && accepted == results.values().stream().mapToInt(Integer::intValue).sum() ? 0 : 1);
  }
}
