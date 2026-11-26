package it.secretsanta;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
@Service
class MailService {
  private final JavaMailSender sender;
  @Value("${spring.mail.host:}") String host;
  @Value("${app.mail-from:}") String from;
  @Value("${app.base-url}") String baseUrl;
  MailService(JavaMailSender sender) { this.sender = sender; }
  boolean configured() { return host != null && !host.isBlank() && from != null && !from.isBlank(); }
  void sendInvitation(Participant p) {
    String url = baseUrl.replaceAll("/$", "") + "/?token=" + p.accessToken;
    send(p, "Il tuo Secret Santa 🎅", "Ciao " + p.name + "! La tua assegnazione è pronta. Apri questo link privato: " + url,
      "Link privato per " + p.email + ": " + url);
  }
  void sendPassword(Participant p, String password) {
    send(p, "La tua password Secret Santa 🎄", "Ciao " + p.name + "! Accedi a " + baseUrl + " con la tua email e questa password: " + password,
      "Password per " + p.email + ": " + password);
  }
  private void send(Participant p, String subject, String text, String logLine) {
    if (!configured()) {
      System.out.println("[SECRET SANTA] " + logLine);
      return;
    }
    SimpleMailMessage msg = new SimpleMailMessage();
    msg.setFrom(from); msg.setTo(p.email);
    msg.setSubject(subject);
    msg.setText(text);
    sender.send(msg);
  }
}
