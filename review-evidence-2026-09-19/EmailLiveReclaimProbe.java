import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.service.PendingSendService;
import fan.summer.fengyu.plugin.email.model.*;
import fan.summer.fengyu.sdk.PluginDatabaseConfig;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
public class EmailLiveReclaimProbe {
 public static void main(String[] args) throws Exception {
  Path dir=Files.createTempDirectory("fengyu-email-review-");
  { var db = new EmailDatabase(new PluginDatabaseConfig("h2","org.h2.Driver","jdbc:h2:mem:review;DB_CLOSE_DELAY=-1","sa","",dir));
   var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
   var service=new PendingSendService(db,(id,req)->{entered.countDown(); try {release.await();}catch(InterruptedException e){throw new RuntimeException(e);} return SendResult.success("sent");},Clock.systemUTC(),Duration.ofMinutes(30));
   var req=new EmailMessageRequest(7,List.of("nobody@example.invalid"),List.of(),List.of(),"Test","Test","Test",List.of());
   String id=service.prepareSingle(req).confirmation().confirmationId();
   try(var executor=Executors.newSingleThreadExecutor()) {
    var future=executor.submit(()->service.confirm(id)); entered.await();
    try(var conn=db.openConnection();var statement=conn.prepareStatement("UPDATE FENGYU_PL_Email_Pending_Send SET updated_at=? WHERE confirmation_id=?")) {
     statement.setObject(1,LocalDateTime.now(ZoneOffset.UTC).minusMinutes(6));statement.setString(2,id);statement.executeUpdate();
    }
    System.out.println("statusWhileSenderStillActive="+service.status(id).orElseThrow().status());
    release.countDown();
    System.out.println("confirmResult="+future.get().status()+", persistedStatus="+service.status(id).orElseThrow().status());
   }
  }
  try(var paths=Files.walk(dir)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
 }
}
