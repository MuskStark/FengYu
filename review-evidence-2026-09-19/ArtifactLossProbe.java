import fan.summer.fengyu.ai.*;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService;
import java.nio.file.*;
import java.io.RandomAccessFile;
import java.util.*;
public class ArtifactLossProbe {
 public static void main(String[] args) throws Exception {
  Path temp = Files.createTempDirectory("fengyu-artifact-review-");
  var grants = new PluginFileGrantService(temp.resolve("grants").toString());
  var store = new ChatArtifactStore(grants, temp.resolve("artifacts"));
  var ref = grants.outputDirectory("test.writer");
  Path output = grants.resolve("test.writer", ref.id()).resolve("result.bin");
  try(var file = new RandomAccessFile(output.toFile(), "rw")) { file.setLength(2L * 1024 * 1024 * 1024 + 1); }
  var result = store.completeTurn("scope", 1L, List.of(new ChatFileGrantService.StagedOutput("test.writer", ref, null)));
  System.out.println("registered=" + result.size() + ", originalOutputExists=" + Files.exists(output));
  try(var paths=Files.walk(temp)) { for(Path p: paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
 }
}
