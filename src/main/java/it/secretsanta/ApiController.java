package it.secretsanta;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api")
class ApiController {
  private final ParticipantRepository repo;
  private final CampaignSettingsRepository settingsRepo;
  private final MailService mail;
  @Value("${app.admin-email}") String adminEmail;
  @Value("${app.admin-key}") String adminKey;
  ApiController(ParticipantRepository repo, CampaignSettingsRepository settingsRepo, MailService mail) { this.repo=repo; this.settingsRepo=settingsRepo; this.mail=mail; }

  record Registration(String name, String email, String photo, String interests, String ideas, String avoid) {}
  record Login(String email, String password) {}
  record ForgotPassword(String email) {}
  record PhotoUpload(String photo) {}
  // shortcut: contatore in memoria, si azzera al riavvio; se l'app va su Internet usare un rate limiter vero
  private final Map<String,Integer> failedLogins = new ConcurrentHashMap<>();
  record PublicParticipant(Long id, String name, String email, String interests, String ideas, String avoid) {}
  record PrivateAssignment(String participant, String recipient, String interests, String ideas, String avoid) {}
  record Status(int count, int max, boolean drawn, boolean mailConfigured) {}
  record Settings(String budget, String notes, int maxParticipants) {}
  record Preferences(String interests, String ideas, String avoid) {}
  record Backup(CampaignSettings settings, List<Participant> participants) {}
  // le entity hanno campi package-private: il mapper del backup li legge direttamente, foto comprese (in base64)
  private static final ObjectMapper BACKUP_JSON=new ObjectMapper().setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

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
    return new Status(all.size(), settings().maxParticipants, !all.isEmpty() && all.stream().allMatch(p->p.drawn), mail.configured());
  }

  private CampaignSettings settings() {
    return settingsRepo.findById(1L).orElseGet(CampaignSettings::new);
  }

  @GetMapping("/settings")
  Settings getSettings() {
    CampaignSettings s=settings();
    return new Settings(s.budget,s.notes,s.maxParticipants);
  }

  @PutMapping("/settings")
  Settings updateSettings(@RequestHeader(value="X-Admin-Email",required=false) String email,
                          @RequestHeader(value="X-Admin-Key",required=false) String key, @RequestBody Settings in) {
    requireAdmin(email,key);
    if (in.maxParticipants()<3 || in.maxParticipants()>500)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Il massimo partecipanti deve essere tra 3 e 500");
    if (in.maxParticipants()<repo.count())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ci sono già "+repo.count()+" iscritti: il massimo non può essere inferiore");
    if ((in.budget()!=null && in.budget().length()>50) || (in.notes()!=null && in.notes().length()>2000))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Budget o note troppo lunghi");
    CampaignSettings s=settings();
    s.budget=in.budget()==null ? null : in.budget().trim();
    s.notes=in.notes();
    s.maxParticipants=in.maxParticipants();
    settingsRepo.save(s);
    return getSettings();
  }

  @PostMapping("/register")
  ResponseEntity<?> register(@RequestBody Registration r) {
    if (r.name()==null || r.name().isBlank() || r.email()==null || !r.email().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome ed email validi sono obbligatori");
    int max=settings().maxParticipants;
    if (repo.count() >= max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limite massimo di "+max+" partecipanti raggiunto");
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
    return repo.findAllByOrderByNameAsc().stream().map(p->new PublicParticipant(p.id,p.name,p.email,p.interests,p.ideas,p.avoid)).toList();
  }

  @GetMapping("/participants/{id}/photo")
  ResponseEntity<byte[]> participantPhoto(@RequestHeader(value="X-Admin-Email",required=false) String email,
                                          @RequestHeader(value="X-Admin-Key",required=false) String key, @PathVariable Long id) {
    requireAdmin(email,key);
    return jpeg(repo.findById(id).map(p->p.photo).orElse(null));
  }

  @DeleteMapping("/participants/{id}")
  Map<String,String> deleteParticipant(@RequestHeader(value="X-Admin-Email",required=false) String email,
                                       @RequestHeader(value="X-Admin-Key",required=false) String key, @PathVariable Long id) {
    requireAdmin(email,key);
    Participant p=repo.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    // dopo l'estrazione cancellare romperebbe gli abbinamenti
    if (p.drawn) throw new ResponseStatusException(HttpStatus.CONFLICT, "Campagna attiva: dopo l'estrazione non si possono cancellare profili");
    repo.delete(p);
    return Map.of("message","Profilo di "+p.name+" cancellato");
  }

  @GetMapping("/backup")
  ResponseEntity<byte[]> backup(@RequestHeader(value="X-Admin-Email",required=false) String email,
                                @RequestHeader(value="X-Admin-Key",required=false) String key) throws IOException {
    requireAdmin(email,key);
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
      .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"secretsanta-backup-"+LocalDate.now()+".json\"").body(backupJson());
  }

  private byte[] backupJson() throws IOException {
    return BACKUP_JSON.writeValueAsBytes(new Backup(settings(), repo.findAll()));
  }

  @PostMapping("/restore") @Transactional
  Map<String,String> restore(@RequestHeader(value="X-Admin-Email",required=false) String email,
                             @RequestHeader(value="X-Admin-Key",required=false) String key, @RequestBody byte[] body) throws IOException {
    requireAdmin(email,key);
    Backup b;
    try { b=BACKUP_JSON.readValue(body,Backup.class); }
    catch(IOException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"File di backup non valido"); }
    if (b.participants()==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"File di backup non valido");
    // copia dei dati attuali prima di sovrascriverli, così un ripristino sbagliato si può annullare
    Path safety=Path.of("data","prima-del-ripristino-"+System.currentTimeMillis()+".json");
    Files.createDirectories(safety.getParent());
    Files.write(safety,backupJson());
    // in batch: subito su DB, altrimenti Hibernate inserisce prima di cancellare e viola l'unicità di email
    repo.deleteAllInBatch();
    // gli id sono generati dal DB: rimappo assignedId sui nuovi id
    Map<Long,Long> ids=new HashMap<>();
    for(Participant p:b.participants()) { Long old=p.id; p.id=null; ids.put(old,repo.save(p).id); }
    for(Participant p:b.participants()) if(p.assignedId!=null) p.assignedId=ids.get(p.assignedId);
    if (b.settings()!=null) { b.settings().id=1L; settingsRepo.save(b.settings()); }
    return Map.of("message","Backup ripristinato: "+b.participants().size()+" iscritti. I dati precedenti sono salvati sul server in "+safety);
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
    String budget=settings().budget;
    for(Participant p:people) {
      try { mail.sendInvitation(p,budget); }
      catch(Exception ex) { System.err.println("Invio email fallito per "+p.email+": "+ex.getMessage()); }
    }
    return Map.of("message","Estrazione completata. Le email sono state inviate se Brevo è configurato; altrimenti i link sono nel log.");
  }

  @GetMapping("/my-assignment")
  PrivateAssignment assignment(@RequestParam String token) {
    Participant p=byToken(token);
    if(!p.drawn) throw new ResponseStatusException(HttpStatus.CONFLICT,"L'estrazione non è ancora stata effettuata");
    // preferenze attuali del destinatario, che può modificarle anche dopo l'estrazione; la copia serve solo alle estrazioni senza assignedId
    Optional<Participant> r=p.assignedId==null ? Optional.empty() : repo.findById(p.assignedId);
    return r.map(x->new PrivateAssignment(p.name,x.name,x.interests,x.ideas,x.avoid))
      .orElse(new PrivateAssignment(p.name,p.assignedName,p.assignedInterests,p.assignedIdeas,p.assignedAvoid));
  }

  @GetMapping("/my-preferences")
  Preferences myPreferences(@RequestParam String token) {
    Participant p=byToken(token);
    return new Preferences(p.interests,p.ideas,p.avoid);
  }

  @PutMapping("/my-preferences")
  Preferences updateMyPreferences(@RequestParam String token, @RequestBody Preferences in) {
    if (Stream.of(in.interests(),in.ideas(),in.avoid()).anyMatch(s->s!=null && s.length()>2000))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ogni campo può avere al massimo 2000 caratteri");
    Participant p=byToken(token);
    p.interests=in.interests(); p.ideas=in.ideas(); p.avoid=in.avoid();
    repo.save(p);
    return new Preferences(p.interests,p.ideas,p.avoid);
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
