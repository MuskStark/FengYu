import fan.summer.fengyu.store.*;
import java.nio.file.*;
import java.util.*;
public class StoreInterop {
  public static void main(String[] args) throws Exception {
    if (args.length != 4) throw new IllegalArgumentException("Usage: StoreInterop.java BASE TRUST_JSON HOST_VERSION QUERY");
    var client = new StoreClient(args[0], new StoreTrustStore(Path.of(args[1])), true, true);
    for (String type : List.of("SKILL", "MCP", "PLUGIN")) {
      var page = client.browse(type, args[3], null, 10);
      if (page.items().isEmpty()) throw new AssertionError("Empty " + type);
      var item = page.items().get(0);
      var detail = client.listing(item.namespace(), item.slug());
      var plan = client.resolve(item.coordinate(), args[2], "macos", "arm64", Map.of());
      if (!plan.resolvable() || plan.plan().isEmpty()) throw new AssertionError("Cannot resolve " + item.coordinate() + " " + plan);
      var release = plan.plan().get(0);
      var ticket = client.ticket(release.releaseId(), null, "macos", "arm64");
      var file = client.download(ticket, ".zip");
      try {
        if (Files.size(file) == 0) throw new AssertionError("Empty download");
        System.out.println("PASS " + type + " catalog/detail/host-resolution/ticket/SHA256/Ed25519 bytes=" + Files.size(file));
      } finally { Files.deleteIfExists(file); }
    }
  }
}
