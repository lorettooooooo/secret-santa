package it.secretsanta;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api")
class ApiController {
  private final ParticipantRepository repo;
  private final MailService mail;
  @Value("${app.admin-key}") String adminKey;
  ApiController(ParticipantRepository repo, MailService mail) { this.repo=repo; this.mail=mail; }

  record Registration(String name, String email, String interests, String ideas, String avoid) {}
  record PublicParticipant(String name, String email, String interests, String ideas, String avoid) {}
  record PrivateAssignment(String participant, String recipient, String interests, String ideas, String avoid) {}
  record Status(int count, boolean drawn, boolean mailConfigured) {}

  private void requireAdmin(String key) {
    if (key == null || !MessageDigest.isEqual(adminKey.getBytes(), key.getBytes()))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Chiave amministratore non valida");
  }

  @GetMapping("/status")
  Status status() {
    List<Participant> all=repo.findAll();
    return new Status(all.size(), !all.isEmpty() && all.stream().allMatch(p->p.drawn), mail.configured());
  }

  @PostMapping("/register")
  ResponseEntity<?> register(@RequestBody Registration r) {
    if (r.name()==null || r.name().isBlank() || r.email()==null || !r.email().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome ed email validi sono obbligatori");
    if (repo.count() >= 20) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limite massimo di 20 partecipanti raggiunto");
    if (repo.findByEmailIgnoreCase(r.email().trim()).isPresent())
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Email già iscritta");
    if (repo.findAll().stream().anyMatch(p->p.drawn))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Iscrizioni chiuse: estrazione già effettuata");
    Participant p=new Participant();
    p.name=r.name().trim(); p.email=r.email().trim().toLowerCase();
    p.interests=r.interests(); p.ideas=r.ideas(); p.avoid=r.avoid();
    repo.save(p);
    return ResponseEntity.ok(Map.of("message","Iscrizione completata"));
  }

  @GetMapping("/participants")
  List<PublicParticipant> participants(@RequestHeader(value="X-Admin-Key",required=false) String key) {
    requireAdmin(key);
    return repo.findAllByOrderByNameAsc().stream().map(p->new PublicParticipant(p.name,p.email,p.interests,p.ideas,p.avoid)).toList();
  }

  @PostMapping("/draw")
  Map<String,String> draw(@RequestHeader(value="X-Admin-Key",required=false) String key) {
    requireAdmin(key);
    List<Participant> people=repo.findAllByOrderByNameAsc();
    if (people.size()<3) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Servono almeno 3 partecipanti");
    if (people.stream().anyMatch(p->p.drawn)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Estrazione già effettuata");
    List<Participant> shuffled=new ArrayList<>(people);
    boolean valid=false;
    for(int attempt=0;attempt<10000;attempt++) {
      Collections.shuffle(shuffled);
      valid=true;
      for(int i=0;i<people.size();i++) if(people.get(i).id.equals(shuffled.get(i).id)){valid=false;break;}
      if(valid) break;
    }
    if(!valid) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Impossibile generare gli abbinamenti");
    for(int i=0;i<people.size();i++) {
      Participant giver=people.get(i), receiver=shuffled.get(i);
      giver.assignedName=receiver.name;
      giver.assignedInterests=receiver.interests;
      giver.assignedIdeas=receiver.ideas;
      giver.assignedAvoid=receiver.avoid;
      giver.drawn=true;
    }
    repo.saveAll(people);
    for(Participant p:people) {
      try { mail.sendInvitation(p); }
      catch(Exception ex) { System.err.println("Invio email fallito per "+p.email+": "+ex.getMessage()); }
    }
    return Map.of("message","Estrazione completata. Le email sono state inviate se SMTP è configurato; altrimenti i link sono nel log.");
  }

  @GetMapping("/my-assignment")
  PrivateAssignment assignment(@RequestParam String token) {
    Participant p=repo.findByAccessToken(token).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    if(!p.drawn) throw new ResponseStatusException(HttpStatus.CONFLICT,"L'estrazione non è ancora stata effettuata");
    return new PrivateAssignment(p.name,p.assignedName,p.assignedInterests,p.assignedIdeas,p.assignedAvoid);
  }
}
