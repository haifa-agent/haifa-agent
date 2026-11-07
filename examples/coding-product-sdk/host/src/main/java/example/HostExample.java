package example;

import io.haifa.coding.host.*;
import io.haifa.coding.api.CodingProtocol.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** External application: only a Host SDK dependency, explicit owner and a trusted launcher control pipe. */
public final class HostExample {
 public static void main(String[] args)throws Exception{
  if(args.length<3)throw new IllegalArgumentException("workspace data-directory trusted-config [assets|-] [port] [grant-seconds] [--owner-ipc]");
  boolean ipc=Arrays.asList(args).contains("--owner-ipc");
  var ingress = browserIngress();
  Optional<BrowserOptions> browser=args.length>3 && !args[3].equals("-")?Optional.of(new BrowserOptions(Path.of(args[3]),"index.html",Duration.ofMinutes(2),Duration.ofSeconds(args.length>5?Long.parseLong(args[5]):900),Duration.ofSeconds(90),4,5,ingress)):Optional.empty();
  if (browser.isEmpty() && ingress.isPresent()) {
   throw new IllegalArgumentException("Browser ingress requires trusted assets");
  }
  var options=new HostOptions(Path.of(args[0]),Path.of(args[1]),Optional.of(Path.of(args[2])),"127.0.0.1",args.length>4?Integer.parseInt(args[4]):0,"External SDK product",browser);
  var json=new ObjectMapper();
  try(var owner=CodingHosts.start(options);var client=owner.embeddedClient();var input=new BufferedReader(new InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8))){
   System.out.println(json.writeValueAsString(Map.of("event","ready","endpoint",owner.endpoint().toString(),"projectId",client.connection().projectId())));System.out.flush();
   String line;
   while((line=input.readLine())!=null){String[] command=line.strip().split(" +");
    if(command[0].equals("close"))break;
    if(command[0].equals("pair")){
     if(!ipc && System.console()==null){System.out.println("Pairing requires a trusted interactive console");System.out.flush();continue;}
     var challenge=owner.newPairingChallenge();
     // This is a trusted owner/launcher pipe, not an application log. Never redirect pairing replies into logs.
     String reply=json.writeValueAsString(Map.of("event","pairing","challenge",challenge.value(),"hostIncarnation",challenge.hostIncarnation(),"expiresAtEpochMillis",challenge.expiresAtEpochMillis()));
     if(ipc){System.out.println(reply);System.out.flush();}else System.console().printf("%s%n",reply);
    }else if(command[0].equals("revoke") && command.length==2){owner.revokeBrowserClient(command[1]);System.out.println(json.writeValueAsString(Map.of("event","revoked","clientId",command[1])));System.out.flush();}
    else System.out.println("Unsupported owner operation");
   }
  }
 }

 private static Optional<BrowserIngress> browserIngress() throws IOException {
  String origin = System.getenv("HAIFA_CODING_EXTERNAL_ORIGIN");
  String proofFile = System.getenv("HAIFA_CODING_PROXY_PROOF_FILE");
  if (origin == null && proofFile == null) return Optional.empty();
  if (origin == null || proofFile == null) {
   throw new IllegalArgumentException("Browser ingress requires both external origin and proxy proof file");
  }
  return Optional.of(new BrowserIngress(URI.create(origin), Files.readString(Path.of(proofFile)).strip()));
 }
}
