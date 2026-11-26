package it.secretsanta;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api")
class ApiController {
  private final ParticipantRepository repo;
  private final MailService mail;
  @Value("${app.admin-email}") String adminEmail;
  @Value("${app.admin-key}") String adminKey;
  ApiController(ParticipantRepository repo, MailService mail) { this.repo=repo; this.mail=mail; }

  record Registration(String name, String email, String photo, String interests, String ideas, String avoid) {}
  record Login(String email, String password) {}
  record ForgotPassword(String email) {}
  record PhotoUpload(String photo) {}
  // shortcut: contatore in memoria, si azzera al riavvio; se l'app va su Internet usare un rate limiter vero
  private final Map<String,Integer> failedLogins = new ConcurrentHashMap<>();
  record PublicParticipant(String name, String email, String interests, String ideas, String avoid) {}
  record PrivateAssignment(String participant, String recipient, String interests, String ideas, String avoid) {}
  record Status(int count, boolean drawn, boolean mailConfigured) {}

  private void requireAdmin(String email, String key) {
    // & invece di && per confrontare sempre entrambi e non rivelare quale dei due è sbagliato
    if (email == null || key == null
      || !(MessageDigest.isEqual(adminEmail.trim().toLowerCase().getBytes(), email.trim().toLowerCase().getBytes())
         & MessageDigest.isEqual(adminKey.getBytes(), key.getBytes())))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Email o password organizzatore non validi");
  }

  @GetMapping("/status")
  Status status() {
    List<Participant> all=repo.findAll();
    return new Status(all.size(), !all.isEmpty() && all.stream().allMatch(p->p.drawn), mail.configured());
  }

  @PostMapping("/register")
  ResponseEntity<?> register(@RequestBody Registration r) {
    if (r.name()==null || r.name().isBlank() || r.email()==null || !r.email().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome ed email validi sono obbligatori");    if (repo.count() >= 20) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limite massimo di 20 partecipanti raggiunto");
    if (repo.findByEmailIgnoreCase(r.email().trim()).isPresent())
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Email già iscritta");
    if (repo.findAll().stream().anyMatch(p->p.drawn))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Iscrizioni chiuse: estrazione già effettuata");
    Participant p=new Participant();
    p.name=r.name().trim(); p.email=r.email().trim().toLowerCase();
    p.photo=decodePhoto(r.photo());
    p.interests=r.interests(); p.ideas=r.ideas(); p.avoid=r.avoid();
    String password=Passwords.generate();
    p.passwordHash=Passwords.sha256(password);
    // mail prima del salvataggio: se l'invio fallisce l'utente può riprovare, invece di restare iscritto senza password
    sendPassword(p,password);
    repo.save(p);
    return ResponseEntity.ok(Map.of("message","Iscrizione completata: controlla la tua email, ti abbiamo inviato la password"));
  }

  private static byte[] decodePhoto(String dataUrl) {
    String prefix="data:image/jpeg;base64,";
    if (dataUrl==null || !dataUrl.startsWith(prefix))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Il selfie è obbligatorio");
    try {
      byte[] photo=Base64.getDecoder().decode(dataUrl.substring(prefix.length()));
      if (photo.length>1_000_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Foto troppo grande");
      return photo;
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Foto non valida");
    }
  }

  private void sendPassword(Participant p, String password) {
    try { mail.sendPassword(p,password); }
    catch(Exception ex) {
      System.err.println("Invio password fallito per "+p.email+": "+ex.getMessage());
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Invio email non riuscito, riprova più tardi");
    }
  }

  @PostMapping("/login")
  Map<String,String> login(@RequestBody Login l) {
    String email=l.email()==null ? "" : l.email().trim().toLowerCase();
    if (failedLogins.getOrDefault(email,0)>=10)
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Troppi tentativi errati: usa «Password dimenticata?»");
    Optional<Participant> p=repo.findByEmailIgnoreCase(email)
      .filter(x->x.passwordHash!=null && l.password()!=null
        && MessageDigest.isEqual(x.passwordHash.getBytes(), Passwords.sha256(l.password()).getBytes()));
    if (p.isEmpty()) {
      failedLogins.merge(email,1,Integer::sum);
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Email o password non validi");
    }
    failedLogins.remove(email);
    return Map.of("token",p.get().accessToken);
  }

  @PostMapping("/forgot-password")
  Map<String,String> forgotPassword(@RequestBody ForgotPassword f) {
    if (f.email()!=null) repo.findByEmailIgnoreCase(f.email().trim()).ifPresent(p->{
      String password=Passwords.generate();
      sendPassword(p,password);
      p.passwordHash=Passwords.sha256(password);
      repo.save(p);
      failedLogins.remove(p.email);
    });
    // stessa risposta in ogni caso, per non rivelare quali email sono iscritte
    return Map.of("message","Se l'email è iscritta, riceverai a breve una nuova password");
  }

  @GetMapping("/participants")
  List<PublicParticipant> participants(@RequestHeader(value="X-Admin-Email",required=false) String email,
                                       @RequestHeader(value="X-Admin-Key",required=false) String key) {
    requireAdmin(email,key);
    return repo.findAllByOrderByNameAsc().stream().map(p->new PublicParticipant(p.name,p.email,p.interests,p.ideas,p.avoid)).toList();
  }

  @PostMapping("/draw")
  Map<String,String> draw(@RequestHeader(value="X-Admin-Email",required=false) String email,
                          @RequestHeader(value="X-Admin-Key",required=false) String key) {
    requireAdmin(email,key);
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
      giver.assignedId=receiver.id;
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
    return Map.of("message","Estrazione completata. Le email sono state inviate se Brevo è configurato; altrimenti i link sono nel log.");
  }

  @GetMapping("/my-assignment")
  PrivateAssignment assignment(@RequestParam String token) {
    Participant p=byToken(token);
    if(!p.drawn) throw new ResponseStatusException(HttpStatus.CONFLICT,"L'estrazione non è ancora stata effettuata");
    return new PrivateAssignment(p.name,p.assignedName,p.assignedInterests,p.assignedIdeas,p.assignedAvoid);
  }

  @GetMapping("/my-photo")
  ResponseEntity<byte[]> myPhoto(@RequestParam String token) {
    return jpeg(byToken(token).photo);
  }

  @PutMapping("/my-photo")
  Map<String,String> updateMyPhoto(@RequestParam String token, @RequestBody PhotoUpload u) {
    Participant p=byToken(token);
    p.photo=decodePhoto(u.photo());
    repo.save(p);
    return Map.of("message","Immagine aggiornata");
  }

  @GetMapping("/recipient-photo")
  ResponseEntity<byte[]> recipientPhoto(@RequestParam String token) {
    Participant p=byToken(token);
    // assignedId manca nelle estrazioni fatte prima che esistesse: niente foto, il resto funziona
    if(!p.drawn || p.assignedId==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return jpeg(repo.findById(p.assignedId).map(r->r.photo).orElse(null));
  }

  private Participant byToken(String token) {
    return repo.findByAccessToken(token).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private static ResponseEntity<byte[]> jpeg(byte[] photo) {
    if(photo==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore()).body(photo);
  }
}
