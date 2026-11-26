# Secret Santa – MVP

Applicazione web per 10–20 partecipanti, realizzata con Java 17, Spring Boot, H2 e frontend HTML/CSS/JavaScript.

## Avvio locale
1. Installa Java 17+ e Maven.
2. Dalla cartella del progetto esegui:
   ```bash
   mvn spring-boot:run
   ```
3. Apri http://localhost:8080
4. Area organizzatore: tocca in sequenza gli angoli alto sx, basso sx, basso dx, alto dx (entro 5 secondi). Credenziali predefinite: `admin@secretsanta.local` / `change-me-now` (variabili `ADMIN_EMAIL` e `ADMIN_KEY`).

La base dati H2 viene salvata nella cartella `./data`.

## Funzioni
- Iscrizione con nome, email e selfie obbligatori, preferenze regalo facoltative.
- Controllo email duplicata e limite massimo di 20 partecipanti.
- Password generata all'iscrizione (es. `Snowball103#`) e inviata via email; a DB solo l'hash SHA-256.
- Login con email e password; "Password dimenticata?" ne genera e invia una nuova.
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
- Cambia `ADMIN_EMAIL` e imposta `ADMIN_KEY` a un valore lungo e casuale.
- Configura HTTPS e un dominio.
- Usa un database gestito con backup.
- Aggiungi rate limiting/CAPTCHA al form, gestione privacy/GDPR e policy di conservazione.
- Proteggi l'area organizzatore con autenticazione vera; la chiave condivisa è solo una protezione minima.
- I link di assegnazione sono segreti: non inoltrarli e non pubblicarli.
- Configura SMTP e verifica il recapito prima dell'estrazione reale.

## API essenziali
- `POST /api/register`
- `POST /api/login` → `{token}`
- `POST /api/forgot-password`
- `GET /api/status`
- `GET /api/participants` con header `X-Admin-Email` e `X-Admin-Key`
- `POST /api/draw` con header `X-Admin-Email` e `X-Admin-Key`
- `GET /api/my-assignment?token=...`
