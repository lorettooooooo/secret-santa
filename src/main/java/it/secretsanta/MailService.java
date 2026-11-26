package it.secretsanta;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.util.*;
// Invio via API HTTPS di Brevo: Railway blocca le porte SMTP sotto il piano Pro
@Service
class MailService {
  private final RestClient brevo;
  @Value("${app.brevo-api-key:}") String apiKey;
  @Value("${app.mail-from:}") String from;
  @Value("${app.base-url}") String baseUrl;
  MailService() {
    SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
    timeouts.setConnectTimeout(5000); timeouts.setReadTimeout(10000);
    brevo = RestClient.builder().baseUrl("https://api.brevo.com/v3").requestFactory(timeouts).build();
  }
  boolean configured() { return apiKey != null && !apiKey.isBlank() && from != null && !from.isBlank(); }
  void sendInvitation(Participant p, String budget) {
    String url = baseUrl.replaceAll("/$", "") + "/?token=" + p.accessToken;
    String text = "Ciao " + p.name + "!\n\nL'estrazione è fatta: dovrai fare un regalo a " + p.assignedName + ".\n\n"
      + "Interessi: " + orDash(p.assignedInterests) + "\n"
      + "Idee regalo: " + orDash(p.assignedIdeas) + "\n"
      + "Da evitare: " + orDash(p.assignedAvoid) + "\n"
      + (budget == null || budget.isBlank() ? "" : "\nBudget: " + budget + "\n")
      + "\nNel tuo profilo trovi anche la sua foto: " + url;
    send(p, "Il tuo Secret Santa 🎅", text, "Link privato per " + p.email + ": " + url);
  }
  private static String orDash(String s) { return s == null || s.isBlank() ? "—" : s; }
  void sendPassword(Participant p, String password) {
    send(p, "La tua password Secret Santa 🎄", "Ciao " + p.name + "! Accedi a " + baseUrl + " con la tua email e questa password: " + password,
      "Password per " + p.email + ": " + password);
  }
  private void send(Participant p, String subject, String text, String logLine) {
    if (!configured()) {
      System.out.println("[SECRET SANTA] " + logLine);
      return;
    }
    brevo.post().uri("/smtp/email").header("api-key", apiKey).contentType(MediaType.APPLICATION_JSON)
      .body(Map.of(
        "sender", Map.of("name", "Secret Santa", "email", from),
        "to", List.of(Map.of("name", p.name, "email", p.email)),
        "subject", subject,
        "textContent", text))
      .retrieve().toBodilessEntity();
  }
}
