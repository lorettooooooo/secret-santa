# Secret Santa – MVP

Applicazione web per 10–20 partecipanti, realizzata con Java 17, Spring Boot, H2 e frontend HTML/CSS/JavaScript.

## Avvio locale
1. Installa Java 17+ e Maven.
2. Dalla cartella del progetto esegui:
   ```bash
   mvn spring-boot:run
   ```
3. Apri http://localhost:8080
4. Chiave amministratore predefinita: `change-me-now`.

La base dati H2 viene salvata nella cartella `./data`.

## Funzioni
- Iscrizione con nome, email e preferenze regalo.
- Controllo email duplicata e limite massimo di 20 partecipanti.
- Estrazione senza auto-assegnazioni e senza destinatari duplicati.
- Link privato con token individuale per mostrare solo il proprio destinatario.
- Email tramite SMTP, se configurato.

## Configurare email
Imposta variabili d'ambiente prima dell'avvio:
- `SMTP_HOST`
- `SMTP_PORT` (es. 587)
- `SMTP_USER`
- `SMTP_PASSWORD`
- `MAIL_FROM`
- `APP_BASE_URL` (URL pubblico dell'app)

Se SMTP non è configurato, l'applicazione NON invia email: stampa i link privati nel log del server. Copiali e inviali individualmente ai partecipanti.

## Sicurezza prima della pubblicazione
Questo è un MVP da provare in locale, non ancora pronto per essere esposto su Internet:
- Cambia `ADMIN_KEY` con un valore lungo e casuale.
- Configura HTTPS e un dominio.
- Usa un database gestito con backup.
- Aggiungi rate limiting/CAPTCHA al form, gestione privacy/GDPR e policy di conservazione.
- Proteggi l'area organizzatore con autenticazione vera; la chiave condivisa è solo una protezione minima.
- I link di assegnazione sono segreti: non inoltrarli e non pubblicarli.
- Configura SMTP e verifica il recapito prima dell'estrazione reale.

## API essenziali
- `POST /api/register`
- `GET /api/status`
- `GET /api/participants` con header `X-Admin-Key`
- `POST /api/draw` con header `X-Admin-Key`
- `GET /api/my-assignment?token=...`
