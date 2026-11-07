package example;

import io.haifa.coding.client.CodingClients;
import io.haifa.coding.api.*;
import io.haifa.coding.api.CodingProtocol.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** External application: Client SDK only. Side effects are submitted once under explicit operator intent. */
public final class ClientExample {
 public static void main(String[] args)throws Exception{
  if(args.length<3)throw new IllegalArgumentException("endpoint create|await|inspect result-file [session] [run] [--approve-fixture]");
  boolean approveFixture=Arrays.asList(args).contains("--approve-fixture");String mode=args[1];Path resultFile=Path.of(args[2]);
  try(var client=CodingClients.connect(URI.create(args[0]))){
   String session,run;boolean responded=false;
   if(mode.equals("create")){
    var command=new Command("command","create","external-request","external-create-key",new CommandTarget(null,null,null,null,null),"external-client-smoke",null,null,List.of());
    var receipt=client.execute(command);if(!receipt.receipt().disposition().equals("accepted"))throw new IllegalStateException("Command was rejected or has unknown outcome; do not replay");session=receipt.session().sessionId();run=receipt.receipt().runId();
   }else{session=args[3];run=args[4];}
   String state="";int historyEntries=0;
   if(!mode.equals("inspect"))try(var observation=client.observe(session)){
    Snapshot current=observation.initial();Set<String> submitted=new HashSet<>();
    long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
    while(System.nanoTime()<deadline){
     for(Entry entry:current.entries())if(entry.interaction()!=null && submitted.add(entry.interaction().id())){
      if(!approveFixture)throw new IllegalStateException("Interaction requires explicit operator input");
      var action=entry.interaction().actions().stream().filter(a->a.kind().equals("respond") && "approve".equals(a.choice()) && a.available()).findFirst().orElseThrow(()->new IllegalStateException("Complete exact approval is unavailable"));
      var t=action.target();var response=client.execute(new Command("command","respond","external-response-"+t.interactionId(),"external-response-key-"+t.interactionId(),new CommandTarget(null,t.sessionId(),t.runId(),t.interactionId(),t.targetRevision()),null,action.choice(),null,List.of()));
      if(!response.receipt().disposition().equals("accepted"))throw new IllegalStateException("Interaction response rejected or unknown; do not replay");responded=true;
     }
     RunFact fact=client.query(new Query("run",new CommandTarget(null,session,run,null,null),null,1,null)).run();state=fact.status();
     historyEntries=client.history(session,32,null).entries().size();
     if(Set.of("COMPLETED","FAILED","CANCELLED","TIMED_OUT").contains(state))break;
     var next=observation.poll(Duration.ofSeconds(2));if(next instanceof Update update)current=CodingPresentationReducer.apply(current,update);else if(next instanceof Resync)throw new IllegalStateException("Observation requires a fresh snapshot");
    }
   }else state=client.query(new Query("run",new CommandTarget(null,session,run,null,null),null,1,null)).run().status();
   new ObjectMapper().writeValue(resultFile.toFile(),Map.of("state",state,"sessionId",session,"runId",run,"historyEntries",historyEntries,"interactionApplied",responded,"hostIncarnation",client.connection().hostIncarnation()));
  }
 }
}
